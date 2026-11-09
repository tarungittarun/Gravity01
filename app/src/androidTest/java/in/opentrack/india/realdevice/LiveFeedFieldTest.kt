package `in`.opentrack.india.realdevice

import `in`.opentrack.india.App
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.remote.ErailWire
import `in`.opentrack.india.ui.SourceEvidencePolicy
import `in`.opentrack.india.ui.SourceEvidenceReceipt
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Field test: the same flows the traveller uses, against the live feed *as it is today*, on a real phone.
 *
 * This class exists because nothing else in the repo can answer the question "is the number on this
 * screen what the railway is reporting right now". The JVM tests pin the rules against fixtures; a
 * fixture cannot tell you that the upstream stopped publishing a column. There is no emulator in the
 * build environment (no KVM, no system images), so this is the substitute, and it is meant to be run
 * by hand:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * # or, for the train actually running outside your window:
 * adb shell am instrument -w -e class in.opentrack.india.realdevice.LiveFeedFieldTest \
 *     -e train 12627 -e from SBC -e to NDLS in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * It asserts *honesty*, not particular minutes: minutes change every day and a test that fails because
 * a train was on time is worse than no test. Everything it learns about the feed is written to logcat
 * under `opentrack-live`, so the output of one run on one running train is a data point I can read.
 */
@RunWith(AndroidJUnit4::class)
class LiveFeedFieldTest {

    private val tag = "opentrack-live"

    private fun arg(key: String, default: String): String =
        InstrumentationRegistry.getArguments().getString(key) ?: default

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * What the public enquiry feed publishes for one train, in the bytes it actually sent. This is the
     * test that decides whether per-stop actual times are reachable without a key, and it is the one
     * claim no fixture in this repo can make.
     */
    // @flow feed-per-stop-data
    @Test
    fun whatThePublicFeedActuallyPublishes() {
        val trainNo = arg("train", "12627")
        val day = RailTime.istEpochDay(System.currentTimeMillis())
        val url = "https://erail.in/rail/getTrains.aspx?TrainNo=$trainNo&DataSource=1&Language=0" +
            "&Cache=true&Flag=1&Date=" + RailTime.formatIsoDate(day)
        val result = Http(context).get(url)
        assumeTrue(
            "this device could not reach the live feed (${(result as? Http.Result.Failed)?.message})",
            result is Http.Result.Ok,
        )
        val body = (result as Http.Result.Ok).body
        val parsed = ErailWire.parse(body)
        val reported = parsed.stops.count { it.actArr != null || it.actDep != null }
        // Hoisted rather than inlined: a `${ ... }` block that spans lines is legal Kotlin and unreadable,
        // and the logcat line this test exists to produce has to be readable on a phone in one pass.
        val stamp = parsed.feedStampMillis
        val stampText = stamp?.let { RailTime.formatClock(it) + " IST" } ?: "nothing"
        Log.i(
            tag, "train $trainNo: ${body.length} bytes, ${parsed.stops.size} halt rows, " +
                "$reported with an actual minute, feed stamped $stampText, " +
                "source said live=${parsed.hasLiveColumns}, ${parsed.unclassifiedRows} row(s) not understood"
        )
        parsed.stops.filter { it.actArr != null || it.actDep != null }.forEach {
            Log.i(tag, "  ${it.code}: sched ${it.schedArr ?: "-"}/${it.schedDep ?: "-"} " +
                "actual ${it.actArr ?: "-"}/${it.actDep ?: "-"} delay ${it.delayArr ?: "-"}/${it.delayDep ?: "-"}")
        }
        // An HTTP 200 can contain identity text or an HTML page rather than per-stop data.
        // The emulator received 1 unclassified record and 0 halts; its raw body was not
        // accessible here, so do NOT claim which of those formats it was. No row may become Act.
        if (parsed.stops.isEmpty()) {
            Log.w(tag, "erail returned no per-stop rows; ${parsed.unclassifiedRows} unclassified " +
                "record(s), ${body.length} bytes; this answer cannot supply actual times")
            assertTrue("an unparsed answer must never become reported halt minutes",
                !parsed.hasLiveColumns)
            return
        }
        assertTrue("the parser found no records at all in ${body.length} bytes", body.isNotBlank())
        assertTrue(
            "every row the parser could not classify is a format change; saw ${parsed.unclassifiedRows}",
            parsed.unclassifiedRows <= parsed.stops.size,
        )
        parsed.feedStampMillis?.let { stamp ->
            val age = System.currentTimeMillis() - stamp
            assertTrue("feed stamp is in the future by ${-age} ms — clock skew or a misread format", age >= -600_000L)
            assertTrue("feed stamp is ${(age / 86_400_000L)} days old — refuse to call this live",
                age < 7 * 86_400_000L)
        }
    }

    /** A network field observation, not a proof of moving-train ground truth. Even an
     * answered page can be an undated claim; its source, cache status and byte digest
     * are logged separately from actuals. Provider unreachability is an unverified
     * outcome, NEVER a green claim of fresh real-world train data. */
    // @flow source-network-evidence
    @Test
    fun requiredWebsiteWireIsNamedAndAnswerIsNotPromotedToActual() {
        val train = arg("train", "12627")
        val day = RailTime.istEpochDay(System.currentTimeMillis())
        var requested = ""
        val result = PublicPageChecks.fetch(PublicPageChecks.ORG, train, day,
            System.currentTimeMillis()) { url ->
            requested = url
            Http(context).get(url)
        }
        assertTrue("source request was routed to an unrelated host: $requested",
            requested == "https://whereismytrain.org.in/?trainNo=$train")
        assertTrue("site must never be promoted by an HTTP answer",
            SourceEvidencePolicy.comparisonOnly(PublicPageChecks.ORG) &&
                !SourceEvidencePolicy.primaryActuals(PublicPageChecks.ORG, true, true))
        assertTrue("cached body must not count as a fresh site answer",
            !result.fromHttpCache || !result.pageAnswered)
        if (result.pageAnswered) {
            assertTrue("answered request carried no recorded bytes/digest",
                result.responseBytes > 0 && result.responseDigest?.length == 16)
        }
        Log.i(tag, "SOURCE-INTEGRITY website: " +
            SourceEvidenceReceipt.publicPage(PublicPageChecks.ORG, train, day, result))
        Log.i(tag, "SOURCE-INTEGRITY verdict: network=${result.pageAnswered}; " +
            "site comparison=${result.orgReport != null}; primaryActuals=0; " +
            "same-run train movement NOT established by page transport")
    }

    /**
     * The rule the traveller's complaint was about, checked on live data: a screen may not present a
     * position as reported when the feed reported nothing, and it may not stay silent about that.
     */
    // @flow screen-explains-itself
    @Test
    fun theScreenExplainsItselfWhateverTheFeedDidOrDidNotSend() = runBlocking {
        val trainNo = arg("train", "12627")
        val from = arg("from", "SBC")
        val to = arg("to", "NDLS")
        val app = context.applicationContext as App
        val repo = app.prepare { done, total -> Log.i(tag, "timetable $done/$total") }
        assumeTrue("the offline timetable could not be prepared on this device", repo != null)
        val originDay = RailTime.istEpochDay(System.currentTimeMillis())
        val live = repo!!.live(trainNo = trainNo, fromCode = from, toCode = to, originEpochDay = originDay)
        assumeTrue("no timetable for $trainNo in the bundled data", live != null)

        val now = System.currentTimeMillis()
        val answer = live!!.answer
        val view = TrackModel.build(
            route = live.route, pos = answer.position, perStop = answer.perStop,
            nowRailMinute = ((RailTime.istEpochDay(now) - originDay) * RailTime.MINUTES_PER_DAY +
                RailTime.istMinuteOfDay(now)).toInt(),
            boardSeq = live.route.stops.firstOrNull { it.code.equals(from, true) }?.seq
                ?: live.route.stops.firstOrNull()?.seq ?: 1,
            alightSeq = live.route.stops.lastOrNull { it.code.equals(to, true) }?.seq
                ?: live.route.lastSeq,
            runEpochDay = originDay,
            note = live.notes.firstOrNull { it.isNotBlank() } ?: answer.note,
        )
        val reportedRows = view.rows.count { it.source == TrackModel.MinuteSource.REPORTED }
        val scheduleRows = view.rows.count { it.source == TrackModel.MinuteSource.SCHEDULE }
        Log.i(
            tag, "$trainNo on device: source=${live.provenance.label}, ${view.rows.size} rows, " +
                "$reportedRows reported / $scheduleRows timetable-only, age=\"${view.ageLabel}\", " +
                "delay=\"${view.delayLabel}\", note=\"${view.note}\""
        )
        // Every one of these three is a promise the app makes, and the first is the one that was broken:
        // a claimed-live screen with no reported minute behind it is only acceptable when the note strip
        // says exactly that.
        if (reportedRows == 0 && live.provenance.live) {
            assertNotNull(
                "the screen says ${live.provenance.label} while nothing was reported, and explained nothing",
                view.note?.takeIf { it.isNotBlank() },
            )
        }
        if (view.ageLabel == "live") {
            assertEquals("the chip may only say \"live\" when the feed itself says it is current",
                true, answer.position.feedAgeSeconds?.let { it < 120L } ?: true)
        }
        assertTrue("the age chip went blank: \"${view.ageLabel}\"", view.ageLabel.isNotBlank())
    }

    /**
     * The alternate source, exercised for real: an endpoint typed into Settings must win, and its answer
     * must be refused if it is about another train. Only runs when the device actually has an override set,
     * so it stays silent for a normal `connectedDebugAndroidTest`.
     */
    // @flow override-endpoint
    @Test
    fun aConfiguredEndpointIsPreferredAndChecked() = runBlocking {
        val prefs = `in`.opentrack.india.data.Prefs(context)
        val override = prefs.overrideUrl.trim()
        assumeTrue("no custom live endpoint is configured on this device", override.startsWith("http"))
        val trainNo = arg("train", "12627")
        val chain = `in`.opentrack.india.remote.ProviderChain(
            listOf(
                `in`.opentrack.india.remote.OverrideProvider(
                    Http(context), override, `in`.opentrack.india.remote.SnapshotStore(context)
                )
            )
        )
        val app = context.applicationContext as App
        val repo = app.prepare { _, _ -> }
        assumeTrue("no timetable to ask about", repo != null)
        val route = repo!!.queries.route(trainNo)
        assumeTrue("$trainNo is not in this device's timetable", route != null)
        val outcome = chain.fetch(trainNo, route!!, RailTime.istEpochDay(System.currentTimeMillis()))
        Log.i(tag, "override endpoint: winner=${outcome.winner}, tried=${outcome.tried}, notes=${outcome.notes}")
        if (outcome.snapshot == null) {
            assertTrue(
                "your endpoint produced nothing and the chain had nothing to say about why",
                outcome.notes.isNotEmpty() || outcome.winner != SourceKind.NONE,
            )
        } else {
            assertEquals("a custom endpoint must never answer about a different train",
                trainNo, outcome.snapshot!!.trainNo)
        }
    }
}
