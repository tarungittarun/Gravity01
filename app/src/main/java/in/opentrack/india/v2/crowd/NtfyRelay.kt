package `in`.opentrack.india.v2.crowd

/**
 * The keyless relay for traveller reports: ntfy.sh's public topics. No key, no account, no server of
 * ours - the relay is a third-party public service, and the UI names it whenever reports render
 * ("reported by travellers", never "official"). ADR-006's consequences section lists this dependency
 * and its honest degradation: unreachable relay = "reports unavailable", never a silent blank.
 *
 * Transport shape matches the rest of v2 (ErailTransport's pattern): one attempt, typed result, no
 * retries - the caller decides what patience looks like. The default wire is the plain java.net stack;
 * tests inject canned wires and touch no network.
 *
 * Topic discipline: the topic IS the train number ("ct-<trainNo>"), so a reader subscribes to exactly
 * one train and a reporter publishes to exactly one train. Nothing personal travels - the report
 * carries a station code, a minute and a time, nothing else (ADR-006 privacy line).
 */
class NtfyRelay(
    private val base: String = "https://ntfy.sh",
    private val fetch: (url: String, method: String, body: String?) -> Wire = ::plainWire,
) {

    sealed class Wire {
        data class Ok(val status: Int, val body: String) : Wire()

        data class Unreachable(val why: String) : Wire()
    }

    fun publish(report: StopReport): Wire {
        val url = "$base/ct-${report.trainNo}"
        return fetch(url, "POST", report.toJson())
    }

    /** One GET of everything recent on the train's topic, parsed into reports; junk lines are dropped. */
    fun pollRecent(trainNo: String): List<StopReport> = pollRecentOrFailure(trainNo).reports

    /**
     * The same poll, honest about WHY it is empty: "no traveller reports yet" and "the relay did not
     * answer" are different sentences a screen must not conflate - a dead relay is not an empty train.
     */
    fun pollRecentOrFailure(trainNo: String): Poll {
        // The per-poll nonce is not decoration: on the owner's phone every poll of the SAME url reads 0
        // while publishes return HTTP OK (paste #19) - the signature of a local middlebox serving a
        // cached empty body. A url never seen before cannot have a cached answer; ntfy ignores unknown
        // query params, so this costs the real relay nothing and takes the cache out of the equation.
        val bust = System.currentTimeMillis()
        val wire = fetch("$base/ct-$trainNo/json?poll=1&since=30m&_=$bust", "GET", null)
        if (wire !is Wire.Ok) return Poll(emptyList(), relayOk = false)
        // rawBody rides along so a caller can verify a publish PARSE-INDEPENDENTLY (a published id is
        // either in the poll's bytes or it is not): paste #19 proved publish-OK + read-zero twice, and
        // without the raw bytes nobody can tell a parser bug from a relay that never returns the message.
        return Poll(parseMessageLines(wire.body), relayOk = true, rawBody = wire.body.take(4000))
    }

    data class Poll(val reports: List<StopReport>, val relayOk: Boolean, val rawBody: String = "")

    private fun parseMessageLines(body: String): List<StopReport> = body.lines()
        .filter { it.contains("\"event\":\"message\"") }
        .mapNotNull { line ->
            val message = extractJsonField(line, "message") ?: return@mapNotNull null
            val unescaped = message.replace("\\\"", "\"").replace("\\\\", "\\")
            StopReport.fromJson(unescaped)
        }

    fun topicFor(trainNo: String): String = "ct-$trainNo"

    companion object {
        /** The plain wire: one attempt, capped read, honest failure kinds. No key, no session, nothing. */
        fun plainWire(url: String, method: String, body: String?): Wire = try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "OpenTrackIndia/1.0 (crowd reports)")
                setRequestProperty("Cache-Control", "no-cache, no-store")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                Wire.Ok(code, text.take(200_000))
            } else {
                Wire.Unreachable("HTTP $code")
            }
        } catch (e: Exception) {
            Wire.Unreachable(e.javaClass.simpleName + ": " + (e.message ?: ""))
        }

        /** The one field ntfy's JSON lines carry our payload in; hand-read for the same no-library reason. */
        internal fun extractJsonField(line: String, key: String): String? {
            val needle = "\"$key\":\""
            val i = line.indexOf(needle)
            if (i < 0) return null
            val sb = StringBuilder()
            var j = i + needle.length
            while (j < line.length) {
                val c = line[j]
                if (c == '\\' && j + 1 < line.length) {
                    sb.append(line[j + 1])
                    j += 2
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    j += 1
                }
            }
            return sb.toString()
        }
    }
}
