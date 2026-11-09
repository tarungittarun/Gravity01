package `in`.opentrack.india.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-side request experiment, tested on the parts that are pure.
 *
 * The variants themselves need a network, so what is pinned here is the reasoning the probe depends on: that
 * every variant is a different question rather than a restatement, that a shape adopted for one train is
 * rebuilt for the next one instead of replayed, and that the report stays silent until something has actually
 * been measured. A diagnostic that lies by omission ("probe: nothing to report") while the network is down is
 * how a support conversation ends up chasing a request shape that was never wrong.
 */
class FeedProbeTest {

    @Test
    fun `a browser result on another host cannot reroute NTES`() {
        val variants = FeedProbe.variants("20651", "2026-10-13", "x")
        val erail = variants.first { it.name == "webview-erail-observe" }
        val ntes = variants.first { it.name == "webview-post" }
        assertFalse(FeedProbe.canAdoptBrowser(erail, true))
        assertFalse(FeedProbe.canAdoptBrowser(ntes, false))
        assertTrue(FeedProbe.canAdoptBrowser(ntes, true))
    }

    @Test
    fun `every variant asks a different question over https`() {
        val vs = FeedProbe.variants("12627", "2026-10-11", NtesWire.plainFor(NtesWire.SUB_LIVE, "12627", 20737L))
        assertTrue("a probe with ${vs.size} variants cannot distinguish much", vs.size >= 6)
        assertEquals("variant names must be unique", vs.size, vs.map { it.name }.distinct().size)
        // Order is a budget decision (18s), v3 per the owner's 10:33 log: the phone REACHED the erail
        // live endpoint (404 was the app's answer to a malformed date) while CI is 403'd - so the
        // erail asks lead, in erail's own date format, and the shipped shape is the control.
        assertEquals("the observation run leads (the guessing era ended with two 404s from the phone)",
            "webview-erail-observe", vs.first().name)
        assertEquals("their own running-status PAGE is asked second (SignalR renders it; we read text)",
            "webview-erail-rspage", vs[1].name)
        assertEquals("the deep-linked page third, settled: its 14:40 verdict was nav + halt names, " +
            "never a time", "webview-erail-rstrain", vs[2].name)
        assertEquals("the SAME page as raw DOM fourth: data their scripts hold invisibly lives there, " +
            "and the scan's verdict rides in every paste", "webview-erail-rstrain-dom", vs[3].name)
        assertEquals("the DESKTOP read fourth: every prior read hit their mobile layout; the one page " +
            "slice never tested (their own footer offers 'Desktop View')", "webview-erail-rstrain-desktop", vs[4].name)
        assertEquals("the human-sequence read fifth: date chip + Show Status + patience + iframes " +
            "(paste #13 fired the tap; #14's full sequence held zero tokens)", "webview-erail-rstrain-dom-tap", vs[5].name)
        assertEquals("the researched mirrors ride the fresh budget: ConfirmTkt's whitelisted servers " +
            "render the cluster's data as HTML", "webview-confirmtkt", vs[6].name)
        assertEquals("the second mirror, its path honestly labelled a guess", "webview-railmitra", vs[7].name)
        assertEquals("the third mirror is probe-only and capture-first", "webview-ixigo", vs[8].name)
        assertEquals("the official app's gateway root: a door BeVigil's scan of the binary named and " +
            "no client ever knocked on - reachability first, paths invented nowhere",
            "get-crisapis-gateway", vs[9].name)
        assertEquals("the site's own script capture follows", "get-erail-sitejs", vs[10].name)
        assertEquals("their own advertised public API is asked after the page reads (runningstatus = actuals)",
            "get-api-runningstatus", vs[11].name)
        assertEquals("and the route API next (per-stop schedule)", "get-api-route", vs[12].name)
        // The two getRunTrain spellings retired here (404 twice from the owner's phone, pastes #10 and
        // #11) - their slots now carry the owner's researched mobile-gateway shape: unverified claims,
        // but testable on the ONE vantage where this host completes TLS. The probe is the experiment.
        assertEquals("the researched NTES app gateway: POST JSON, queryType 6, dalvik, dd-MMM-yyyy",
            "post-appservand-json", vs[13].name)
        assertTrue("gateway body must be the researched query, regenerable per train",
            vs[13].body!!.contains("\"queryType\":\"6\"") && vs[13].body!!.contains("\"trainNo\":\"12627\""))
        assertEquals("the same gateway through the browser engine (the transport axis that separates " +
            "this phone's two stacks)", "webview-appservand", vs[14].name)
        assertEquals("the shipped shape follows as the control", "post-dalvik-json", vs[15].name)
        assertEquals("the legacy-cipher ask last-in-window: the SAME correct shape with static-RSA suites " +
            "offered - the working OSS client widens for exactly this reason",
            "post-appservand-legacytls", vs[16].name)
        // The run window is the first MAX_TRIES; everything beyond must be legacy leftovers, never a
        // settled page read - the reads that need the fresh budget must sit inside the window.
        assertTrue("beyond the window only legacy controls may live: got " +
            vs.drop(17).joinToString(",") { it.name },
            vs.drop(17).none {
                it.name.startsWith("webview-erail-rstrain") || it.name.contains("confirmtkt") ||
                    it.name.contains("crisapis")
            })
        for (v in vs) {
            assertTrue("${v.name} must not send data in the clear", v.url.startsWith("https://"))
            assertTrue("${v.name} needs an explanation for the report", v.note.length > 8)
            // The control host is the only variant allowed to be non-feed, and it is a GET of a page.
            if (v.name == "get-erail-control") assertNull("${v.name} must not POST", v.body)
        }
        // A reachability control that is a different *host* is what separates "the feed refused us" from
        // "this device has no route", so at least two distinct hosts must appear in the matrix.
        val hosts = vs.map { it.url.substringAfter("://").substringBefore("/") }.distinct()
        assertTrue(
            "hosts must actually be extracted (an empty or single-element set would make this vacuous): $hosts",
            hosts.size >= 3,
        )
        assertEquals(
            "probe must span more than one host",
            true,
            vs.map { it.url.substringAfter("://").substringBefore("/") }.distinct().size >= 2,
        )
    }

    @Test
    fun `the browser channel probes the transport itself and starts disbelieved`() {
        val vs = FeedProbe.variants("12627", "2026-10-11", NtesWire.plainFor(NtesWire.SUB_LIVE, "12627", 20737L))
        val browser = vs.filter { it.browser }
        assertTrue("the transport must be a probeable variable, and none of ${vs.size} variants used the browser engine",
            browser.isNotEmpty())
        assertFalse("nothing may rely on the browser transport before a device proves the engine is accepted",
            FeedProbe.browserWorks)
        // The session variant has to visit the site first, otherwise it asks the same question as the plain
        // browser POST twice and the cookie - the one variable it was added for - goes untested.
        assertTrue("a warm variant must warm, and a bare one must not",
            browser.any { it.warm.isNotBlank() } && browser.any { it.warm.isBlank() })
        // The form variant carries the envelope as a field, so it too must be regenerated per train: a probe
        // that replays one train's encrypted answer across the matrix would report a false green.
        val form = vs.first { it.name == "webview-post-form" }
        val rebuilt = FeedProbe.rebuild(form, "22691", 20738L)
        assertTrue("the form body must stay a jsonIn field across a rebuild, got ${rebuilt.body}",
            rebuilt.body!!.startsWith("jsonIn=") && rebuilt.body != form.body)
        // The invariant the 159-byte mistake taught: a body is only ever sent with the content type that
        // describes it. The renderer this code used to go through could not set one at all, which is why
        // "we tried a browser and it answered" was never a finding about the payload.
        for (v in browser) {
            if (v.body != null) {
                assertTrue(v.name + " must declare the content type its body actually is",
                    v.headers.keys.any { it.equals("Content-Type", ignoreCase = true) })
            }
        }
    }

    @Test
    fun `an adopted shape is rebuilt for the train being asked about`() {
        val vs = FeedProbe.variants("12627", "2026-10-11", NtesWire.plainFor(NtesWire.SUB_LIVE, "12627", 20737L))
        val post = vs.first { it.name == "post-dalvik-json" }
        val other = FeedProbe.rebuild(post, "22691", 20738L)
        assertEquals("the URL is the same shape", post.url, other.url)
        assertFalse(
            "the envelope must be regenerated, not replayed",
            other.body == post.body,
        )
        // The body is an encrypted envelope, so the check is on the *shape* of the request and on the plain
        // variant, whose body is the query string and therefore readable here.
        val plainVariant = vs.first { it.name == "post-plain-body" }
        val rebuiltPlain = FeedProbe.rebuild(plainVariant, "22691", 20738L)
        assertNotNull(rebuiltPlain.body)
        assertTrue(
            "rebuilt body: ${rebuiltPlain.body}",
            rebuiltPlain.body!!.contains("trainNo=22691") && rebuiltPlain.body!!.contains("startDate=12-Oct-2026"),
        )
        assertFalse(
            "a control host that never resolves is not a control",
            vs.any { it.url.contains("howtherail") },
        )
        assertTrue(
            "the no-key control must exist: it separates 'this app cannot do TLS' from 'the feed refuses us'",
            vs.any { it.name == "get-railradar-no-key" },
        )
        val pinned = vs.filter { it.pin4 }
        assertEquals(
            "exactly the two pinned probes, no more: " + pinned.map { it.name },
            2, pinned.size,
        )
        assertTrue(
            "one pinned line must be the shipped request and the other a host known to answer, or a green " +
                "pinned result proves nothing",
            pinned.any { it.name == "post-ipv4-pinned" && it.body != null } &&
                pinned.any { it.name == "get-erail-ipv4" && it.body == null },
        )
        // The pin has to survive a rebuild: adoption hands the app a shape, and a shape that silently loses
        // its route on the way to a later refresh would show live data working once and then never again.
        val rebuiltPinned = FeedProbe.rebuild(pinned.first { it.pin4 && it.body != null }, "12927", 20738L)
        assertTrue("rebuild dropped the pin", rebuiltPinned.pin4)
        val getNtes = vs.first { it.name == "get-ntes-web" }
        assertTrue(
            "a GET variant must carry the new train number",
            FeedProbe.rebuild(getNtes, "22691", 20738L).url.contains("trainNo=22691"),
        )
    }

    @Test
    fun `the report says nothing until a probe has run`() {
        assertNull(
            "no variant may be adopted before an answer is verified",
            FeedProbe.adoptedName,
        )
        assertFalse(FeedProbe.ready)
        assertEquals("", FeedProbe.render())
        assertNull(FeedProbe.adoptedFor("12627", 20737L))
    }

    @Test
    fun `the legacy web shape carries the headers that endpoint has always demanded`() {
        val vs = FeedProbe.variants("12627", "2026-10-11", "x")
        val legacy = vs.first { it.name == "get-ntes-web" }
        // Not decoration: that endpoint answers a request that presents itself as the NTES site, and answers
        // with a redirect or a closed socket to one that does not. Getting this into the probe is what makes
        // the difference between a fix and a mystery in the report.
        assertTrue(legacy.headers["Referer"]?.contains("enquiry.indianrail.gov.in") == true)
        assertTrue(legacy.headers.containsKey("X-Requested-With"))
    }

    @Test
    fun `the egress control never carries an ip address into the report`() {
        val trace = "ip=103.21.58.11\nts=1760181000\nvisit_scheme=https\nuag=okhttp\ncolo=BOM\n" +
            "http=http/1.1\nloc=IN\nsloc=in\nsla=\nslop=tls\notxt=1\nproxy=none\nip_version=4\n"
        val summary = FeedProbe.egressSummary(trace)
        assertTrue("summary was: $summary", summary.contains("loc=IN"))
        assertTrue("summary was: $summary", summary.contains("proxy=none"))
        assertFalse("the device's address must not be copyable", summary.contains("103.21.58.11"))
        assertFalse(summary.contains("ip="))
        assertEquals("answered, fields unreadable", FeedProbe.egressSummary("<html>no fields here</html>"))
    }
}
