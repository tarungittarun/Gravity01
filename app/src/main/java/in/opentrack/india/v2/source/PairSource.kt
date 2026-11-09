package `in`.opentrack.india.v2.source

import `in`.opentrack.india.v2.contract.PairListing
import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind

/**
 * The question "what runs between these two stations", as the source layer receives it.
 *
 * [budgetMs] travels with the question because the budget belongs to whoever owns the screen, not to the
 * transport: a diagnostic must never extend the time to first row, and one request per search is the whole
 * etiquette budget (2.5). No retries live below this line.
 */
data class PairQuery(val fromCode: String, val toCode: String, val budgetMs: Int = 12_000)

/**
 * Transport, injected (5.1): the v2 source layer never opens a socket, so parsing and state decisions are
 * testable on a laptop and CI can prove the whole flow against a fake before any egress exists. The real
 * transports are the WebView (the only thing that has returned NTES bytes on a phone) and, for erail, a
 * plain GET.
 */
fun interface PairTransport {
    /** Exactly one attempt. The implementation owes the caller a [PairWire], never a throw. */
    fun fetch(query: PairQuery): PairWire
}

/** How the wire failed, typed so that "could not look" can never masquerade as an answer. */
enum class PairWireFailure { NETWORK, TIMEOUT, HTTP_STATUS, TLS }

/** What the transport got back: a body with its fetch time, or an honest nothing. */
sealed class PairWire {
    /** [fetchedAtMillis] is when the wire answered - the parser stamps provenance with it, not with the wall clock of parsing. */
    data class Ok(val body: String, val fetchedAtMillis: Long) : PairWire()

    data class Unreachable(val failure: PairWireFailure, val detail: String) : PairWire()
}

/**
 * Turns a captured-shape body into facts. The erail implementation is WRITTEN ONLY AGAINST A CAPTURED
 * PAYLOAD (4.5): the interface is the seam, and until the capture from CI lands there is deliberately no
 * erail parser in this repo - guessing key names is how v1 shipped a wrong timetable with confidence.
 */
fun interface PairParser {
    fun parse(body: String, fetchedAtMillis: Long): PairParse
}

/** The three things a parse can be. `Unreadable` is a fact about the upstream, shown, never papered over. */
sealed class PairParse {
    data class Rows(val listing: PairListing) : PairParse()

    /** The source answered and said: nothing runs between these stations in the window. A real event. */
    object NothingRunning : PairParse()

    /** HTTP 200, but not the shape we captured: the upstream changed. Never parsed by guessing (10.3). */
    data class Unreadable(val sample: String) : PairParse()
}

/**
 * Every way a pair search can end, one type per user-visible state (8.2).
 *
 * The whole point is the distinction v1 destroyed: [AnsweredEmpty] ("the live source says nothing runs")
 * and [CouldNotLook] ("we never reached a live source") are different types here, because on a screen the
 * first is a list and the second is an apology - and a passenger who reads the second as the first is told
 * the route is dead when only our fetch was. [ProtocolChanged] is our bug class, not the railway's, and is
 * kept separate so the copy can say the feed's protocol may have changed. [DisabledByPolicy] is the 9.6
 * feature-flag state: a source we switched off says so rather than quietly disappearing.
 */
sealed class PairAnswer {
    data class Listing(val listing: PairListing) : PairAnswer()

    data class AnsweredEmpty(val provenance: Provenance) : PairAnswer()

    data class CouldNotLook(val detail: String) : PairAnswer()

    data class ProtocolChanged(val sample: String) : PairAnswer()

    data class DisabledByPolicy(val source: String) : PairAnswer()
}

/**
 * One live pair source (M2 = C15). State machine, nothing else: one query in, one typed answer out, no
 * caching (the store layer owns TTLs), no merging (the domain layer owns precedence), no retries, no
 * diagnostics on the request path.
 */
class PairSource(
    private val sourceName: String,
    private val transport: PairTransport,
    private val parser: PairParser,
    /** Build-time flag per source (9.6): a broken upstream is switched off in one commit, and says so. */
    private val enabled: Boolean = true,
) {
    fun search(query: PairQuery): PairAnswer {
        if (!enabled) return PairAnswer.DisabledByPolicy(sourceName)
        return when (val wire = transport.fetch(query)) {
            is PairWire.Unreachable ->
                PairAnswer.CouldNotLook("${wire.failure}: ${wire.detail}")
            is PairWire.Ok ->
                when (val parsed = parser.parse(wire.body, wire.fetchedAtMillis)) {
                    is PairParse.Rows -> PairAnswer.Listing(parsed.listing)
                    is PairParse.NothingRunning ->
                        PairAnswer.AnsweredEmpty(
                            Provenance(SourceKind.LIVE_PAIR, sourceName, wire.fetchedAtMillis)
                        )
                    is PairParse.Unreadable -> PairAnswer.ProtocolChanged(parsed.sample)
                }
        }
    }
}
