package `in`.opentrack.india.v2.source

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The real erail transport: one HTTPS GET, budget from the query, no retries, no fallbacks - those are
 * the caller's decisions (a source that silently retries is a diagnostic that outranks the user's
 * request, lesson 7). `openConnection` is injected so a laptop test can serve a canned response
 * without any egress (5.1).
 *
 * Stays free of android.* so the same class runs on the JVM in CI; on the device it is constructed
 * once per search and used exactly once.
 */
class ErailTransport(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PairTransport {

    override fun fetch(query: PairQuery): PairWire =
        fetchUrl(pairUrl(query.fromCode, query.toCode), query.budgetMs)

    /**
     * One GET for one number's identity tile (same record shape as the pair reply - the 2026-10-12
     * captures). One request per screen open is the etiquette budget (2.5); no retries here either.
     */
    fun fetchTrainNo(trainNo: String, budgetMs: Int): PairWire =
        fetchUrl(trainNoUrl(trainNo), budgetMs)

    private fun fetchUrl(url: String, budgetMs: Int): PairWire {
        val started = nowMillis()
        return try {
            val conn = openConnection(url)
            conn.connectTimeout = budgetMs
            conn.readTimeout = budgetMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            try {
                val code = conn.responseCode
                if (code != 200) {
                    return PairWire.Unreachable(PairWireFailure.HTTP_STATUS, "HTTP $code")
                }
                PairWire.Ok(conn.inputStream.use { readCapped(it, MAX_BYTES) }, nowMillis())
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            PairWire.Unreachable(classify(e, started), e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            // A non-IO throw (RuntimeException from a stack we do not own) is still "we could not look",
            // typed, with the class named - never a crash that pretends the railway failed.
            PairWire.Unreachable(PairWireFailure.NETWORK, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Body bytes, hard-capped: a feed gone wrong must not turn into an OOM on a 2 GB phone. */
    private fun readCapped(input: java.io.InputStream, cap: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < cap) {
            val n = input.read(chunk, 0, minOf(chunk.size, cap - total))
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toString("UTF-8")
    }

    private fun classify(e: IOException, startedMillis: Long): PairWireFailure {
        val msg = (e.message ?: "").lowercase()
        return when {
            // TLS first: "handshake timed out" contains "timed out" and IS a TLS failure - the
            // 898b196 run caught the timeout keyword swallowing it (someone in the middle is not
            // "their site is slow").
            "ssl" in msg || "tls" in msg || "handshake" in msg ||
                "certificate" in msg -> PairWireFailure.TLS
            "timeout" in msg || "timed out" in msg -> PairWireFailure.TIMEOUT
            else -> PairWireFailure.NETWORK
        }
    }

    companion object {
        const val BASE = "https://erail.in/rail/getTrains.aspx"
        const val UA = "Dalvik/2.1.0 (Linux; Android 11)"

        /** The pair query exactly as the probe ladder proved it (the first candidate that answers). */
        fun pairUrl(fromCode: String, toCode: String): String =
            "${BASE}?Station_From=$fromCode&Station_To=$toCode" +
                "&Trains=BOTH&Sort_Type=PVT&Language=0&DataSource=1"

        /**
         * The by-number identity query, exactly as the CI probe fetches it (the capture comment of
         * 2026-10-12 carries the reply bodies). Same keyless GET, one number in, one tile record out.
         */
        fun trainNoUrl(trainNo: String): String = "${BASE}?TrainNo=$trainNo&DataSource=1&Language=0"

        private const val MAX_BYTES = 400_000
    }
}
