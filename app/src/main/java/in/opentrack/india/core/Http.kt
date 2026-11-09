package `in`.opentrack.india.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import `in`.opentrack.india.core.Diary
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * The app's entire networking surface: one GET helper.
 *
 * Why not OkHttp/Retrofit: they would add ~350 kB + a transitive tree, and every feature we need of
 * them — a timeout, a retry, a gzip header, a response cache — is ~40 lines here. The other reason is
 * the failure mode this app keeps hitting: a proxy-free client must degrade *predictably* when the
 * endpoint is slow or hostile, and with a 40-line helper the degradation is auditable rather than
 * buried in an interceptor chain.
 *
 * Cache policy: every successful body is written to `cacheDir/http/<sha1>`. On failure we serve the
 * cached copy and mark it stale — that is what makes "live status" still show *something* (with an
 * explicit age) inside a tunnel, and it means a provider outage degrades freshness instead of
 * blanking the screen.
 */
class Http(context: Context) {

    private val cacheRoot = File(context.cacheDir, "http").apply { mkdirs() }

    /**
     * Every socket operation in the app is serialised on this monitor.
     *
     * The reason is a bug this class caused rather than one it observed. Android's HttpURLConnection keeps a
     * process-wide pool of keep-alive sockets, and `disconnect()` does not merely drop *this* connection - it
     * closes the pooled socket. With one shared [Http] and several things fetching at once (the startup read in
     * `App`, the 60-second auto-refresh, a fallback source, a diagnostic probe), thread A could `disconnect()`
     * while thread B was still mid-handshake on that socket, and B would see `IOException: closed` without
     * anything having been wrong with its request.
     *
     * That is what a live-feed report from a phone in Bengaluru looked like: three unrelated hosts, every one
     * "closed" in about 400 ms, including a site this app has never failed against. A field that rejects us
     * answers; a torn connection pool hangs up. Serialising costs the app almost nothing (all of its traffic is
     * a few small requests per minute, to one public endpoint that deserves that pacing anyway) and buys the
     * only measurement of the feed that can be trusted.
     */
    private val socketLock = Any()

    private inline fun <T> serial(block: () -> T): T = synchronized(socketLock) { block() }

    sealed class Result {
        data class Ok(val body: String, val fromCache: Boolean, val ageMillis: Long) : Result()
        data class Failed(val kind: Kind, val message: String) : Result()
        enum class Kind { OFFLINE, TIMEOUT, HTTP, EMPTY, PARSE }
    }

    /**
     * Blocking GET. Call from an IO dispatcher.
     *
     * @param retries 1 retry for a GET is free of side effects and covers the single dropped
     *   connection that dominates failures on mobile networks; more would just stack latency.
     */
    fun get(
        url: String,
        timeoutMillis: Int = DEFAULT_TIMEOUT,
        retries: Int = 1,
        headers: Map<String, String> = emptyMap(),
        pinIpv4: Boolean = false,
        budgetMillis: Int = 0,
        /** A refusal must remain visible to callers that pace a source after HTTP 403/429. */
        allowCachedOnFailure: Boolean = true,
        /** Refused website redirects are not permission to ask another URL. */
        followRedirects: Boolean = true,
    ): Result {
        var last: Result = Result.Failed(Result.Kind.EMPTY, "not attempted")
        var attempt = 0
        val deadline = if (budgetMillis > 0) System.currentTimeMillis() + budgetMillis else 0L
        while (attempt <= retries) {
            var left = clampTimeout(deadline, timeoutMillis)
            if (left == 0) {
                // Never skip the *first* attempt. A caller that budgeted two seconds wants a request that may
                // fail in two seconds, not a refusal to ask the question at all - the budget exists to cut the
                // ladder's later rungs, which are what multiply into half a minute.
                if (attempt > 0) { logCut("GET", url, budgetMillis, last); break }
                left = if (timeoutMillis < MIN_ATTEMPT_MS) timeoutMillis else MIN_ATTEMPT_MS
            }
            attempt++
            last = tryOnce(url, left, headers, pinIpv4, followRedirects)
            Diary.get()?.log("HTTP") {
                "GET " + hostOf(url) + " try#" + attempt + (if (pinIpv4) " pinned-v4" else "") +
                    " -> " + verdict(last)
            }
            if (last is Result.Ok || last !is Result.Failed) return last
            if (last.kind == Result.Kind.HTTP) break            // 4xx/5xx will not fix itself
            if (attempt <= retries) sleep(300L * attempt)
        }
        if (last is Result.Failed && !pinIpv4 && last.kind != Result.Kind.OFFLINE &&
            !httpStatusResponse(last) && clampTimeout(deadline, timeoutMillis) > 0
        ) {
            // A completed HTTP refusal (especially 429) is NOT a broken IPv6 socket.
            // Do not send the same refused request again to another address family.
            // One unprompted second chance, pinned to an A record. A phone on Wi-Fi whose IPv6 path is
            // half-alive sees exactly this: the first address returned is AAAA, the handshake dies as
            // "closed" a few hundred milliseconds in, and a browser on the same network loads the page
            // because it retries the other family. Chrome's advantage is not magic - it is that it tries
            // twice. Doing the same here fixes every host the app talks to, not only the feed.
            when (val pinned = tryOnce(url, clampTimeout(deadline, timeoutMillis), headers, true, followRedirects)) {
                is Result.Ok -> {
                    Diary.get()?.log("HTTP", "GET " + hostOf(url) + " recovered on the pinned IPv4 route")
                    return pinned
                }
                else -> last = if (verdict(last).length >= verdict(pinned).length) last else pinned
            }
        }
        if (resolverBlip(last) && clampTimeout(deadline, timeoutMillis) > 0) {
            // Not "offline": the name service blinked. A phone's log showed five hosts failing to resolve
            // within 70 ms of each other, after every one of them had resolved cleanly a minute earlier - what
            // a filtering private-DNS resolver does when a burst outruns it. One pause and one retry costs
            // 600 ms on the rare bad minute and hides the class of failure from the traveller entirely. If the
            // name is genuinely gone, the retry fails identically and the message the user reads is unchanged.
            sleep(600L)
            val again = tryOnce(url, clampTimeout(deadline, timeoutMillis), headers,
                followRedirects = followRedirects)
            Diary.get()?.log("HTTP", "GET " + hostOf(url) + " after a resolver blip -> " + verdict(again))
            if (again is Result.Ok) return again
            last = again
        }
        if (last is Result.Failed && allowCachedOnFailure) {
            Diary.get()?.log("HTTP", "GET " + hostOf(url) + " served the response cache instead: " + verdict(last))
            cached(url)?.let { (body, age) ->
                Log.d(TAG, "serving cached body (${age / 1000}s old) after ${last.kind}")
                return Result.Ok(body, fromCache = true, ageMillis = age)
            }
        }
        return last
    }

    /**
     * Blocking POST with an explicit body and headers. Uncached on purpose: the cache here is keyed by URL,
     * and a live-status POST asks a different question on every call — caching it would let the app serve a
     * ten-minute-old answer as though it had just arrived, which is the one thing this product must not do.
     */
    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = DEFAULT_TIMEOUT,
        retries: Int = 0,
        tls12: Boolean = false,
        pinIpv4: Boolean = false,
        budgetMillis: Int = 0,
        /** Offer the legacy static-RSA suites indianrail.gov.in is documented (by a working open-source
            client) to REQUIRE and modern defaults to drop. Trust unchanged - only what we OFFER widens. */
        legacyCiphers: Boolean = false,
    ): Result {
        var attempt = 0
        var last: Result = Result.Failed(Result.Kind.EMPTY, "not attempted")
        val deadline = if (budgetMillis > 0) System.currentTimeMillis() + budgetMillis else 0L
        while (attempt <= retries) {
            var left = clampTimeout(deadline, timeoutMillis)
            if (left == 0) {
                // Never skip the *first* attempt. A caller that budgeted two seconds wants a request that may
                // fail in two seconds, not a refusal to ask the question at all - the budget exists to cut the
                // ladder's later rungs, which are what multiply into half a minute.
                if (attempt > 0) { logCut("POST", url, budgetMillis, last); break }
                left = if (timeoutMillis < MIN_ATTEMPT_MS) timeoutMillis else MIN_ATTEMPT_MS
            }
            attempt++
            last = tryPost(url, body, headers, left, tls12, pinIpv4, legacyCiphers)
            Diary.get()?.log("HTTP") {
                "POST " + hostOf(url) + " try#" + attempt + (if (tls12) " tls1.2" else "") +
                    (if (pinIpv4) " pinned-v4" else "") + " body=" + body.length + "B -> " + verdict(last)
            }
            if (last is Result.Ok) return last
            if (last is Result.Failed && last.kind == Result.Kind.HTTP) break
            if (attempt <= retries) sleep(400L * attempt)
        }
        if (resolverBlip(last) && clampTimeout(deadline, timeoutMillis) > 0) {
            sleep(600L)
            val again = tryPost(url, body, headers, clampTimeout(deadline, timeoutMillis), tls12, pinIpv4, legacyCiphers)
            Diary.get()?.log("HTTP", "POST " + hostOf(url) + " after a resolver blip -> " + verdict(again))
            if (again is Result.Ok) return again
            last = again
        }
        if (last is Result.Failed && !pinIpv4 && last.kind != Result.Kind.OFFLINE &&
            clampTimeout(deadline, timeoutMillis) > 0
        ) {
            val pinned = tryPost(url, body, headers, clampTimeout(deadline, timeoutMillis), tls12, true, legacyCiphers)
            Diary.get()?.log("HTTP") {
                "POST " + hostOf(url) + " pinned-v4 retry -> " + verdict(pinned)
            }
            if (pinned is Result.Ok) return pinned
            last = pinned
        }
        return last
    }

    /**
     * The A record for a host, or null. Resolution failures are swallowed here on purpose: the caller has
     * its own, better-informed error path, and "no IPv4 answer" must not become a second failure message.
     */
    /**
     * The timeout for the next attempt, given a wall-clock budget for the whole call. Zero means "do not
     * start one": an attempt that cannot finish inside the budget is not a second chance, it is the traveller
     * waiting.
     *
     * This exists because the ladder in this class - a retry, a pinned-v4 second chance, a resolver-blip
     * retry - was designed one socket at a time, so it multiplies. Two device reports show what that costs
     * when a network is merely congested rather than refusing: a live refresh of 31.5 s, another of 44.1 s,
     * and 162 s for a single probe variant in which every individual attempt had already failed at 5 s. The
     * rungs stay; each now happens inside a budget the caller chooses.
     */
    private fun clampTimeout(deadline: Long, configured: Int): Int {
        if (deadline == 0L) return configured
        val left = (deadline - System.currentTimeMillis()).toInt()
        return if (left < MIN_ATTEMPT_MS) 0 else if (left < configured) left else configured
    }

    /** Says out loud that the ladder was cut, so a fast failure in a log is not read as a new symptom. */
    private fun logCut(verb: String, url: String, budgetMillis: Int, last: Result) {
        if (budgetMillis <= 0) return
        Diary.get()?.log("HTTP") {
            verb + " " + hostOf(url) + " stopped waiting at " + budgetMillis +
                "ms of budget - the retry ladder was not finished (last: " + verdict(last) + ")"
        }
    }

    private fun firstIpv4(url: String): java.net.InetAddress? = runCatching {
        java.net.InetAddress.getAllByName(java.net.URL(url).host)
            .filterIsInstance<java.net.Inet4Address>().firstOrNull()
    }.getOrNull()

    /**
     * Ask the connection to use one specific address for the whole route. Android exposes this as
     * setFixedRouteToAddress (API 20+, hence CHANGE_NETWORK_STATE in the manifest) and refuses loopback and
     * link-local targets itself, so a poisoned DNS answer cannot point the app at the device.
     */
    private fun applyPin(conn: HttpURLConnection, addr: java.net.InetAddress?, tag: String) {
        if (addr == null) {
            Diary.get()?.log("HTTP", tag + " no A record to pin - the host has no IPv4 answer")
            return
        }
        // Reflective on purpose. The method is Android's own extension to java.net.HttpURLConnection
        // (API 20, hence CHANGE_NETWORK_STATE) rather than a member the JDK copy has, and a compile against
        // the bootclasspath's java.net resolves to the one without it - so an unresolved reference at build
        // time said nothing about runtime. Looking it up keeps the pin available where it exists and quietly
        // absent where it does not, which is the correct behaviour for a fallback either way.
        runCatching {
            conn.javaClass.getMethod("setFixedRouteToAddress", java.net.InetAddress::class.java)
                .invoke(conn, addr)
        }.onFailure {
            Diary.get()?.log("HTTP", tag + " pin unavailable: " + it.javaClass.simpleName + " " + it.message)
        }
    }

    private fun tryPost(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutMillis: Int,
        tls12: Boolean = false,
        pin4: Boolean = false,
        legacyCiphers: Boolean = false,
    ): Result = serial {
        var conn: HttpURLConnection? = null
        var bodyRead = false
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Connection", "close")
                for ((k, v) in headers) setRequestProperty(k, v)
                if ((tls12 || legacyCiphers) && this is HttpsURLConnection) {
                    this.sslSocketFactory = if (legacyCiphers) legacyCipherFactory() else tls12Factory()
                }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code >= 400) return Result.Failed(Result.Kind.HTTP, "HTTP $code")
            val text = HttpBody.read(conn.inputStream, conn.contentEncoding)
            bodyRead = true
            if (text.isBlank()) Result.Failed(Result.Kind.EMPTY, "empty body")
            else Result.Ok(text, fromCache = false, ageMillis = 0)
        } catch (e: SocketTimeoutException) {
            Result.Failed(Result.Kind.TIMEOUT, failText(e, "timeout"))
        } catch (e: IOException) {
            val offline = e.message?.let {
                it.contains("Unable to resolve host") || it.contains("ENETUNREACH") || it.contains("ENONET")
            } == true
            // The class name travels with the message on purpose. "The live feed refused the request (closed)"
            // was true and useless: an SSLException closed mid-handshake, a server that answers HTTP 403, and a
            // carrier middlebox that resets the socket are different bugs with different fixes, and only the
            // device can tell them apart. Costs 20 characters, saves a round trip measured in days.
            Result.Failed(
                if (offline) Result.Kind.OFFLINE else Result.Kind.HTTP,
                "${e.javaClass.simpleName}: ${e.message ?: "io error"}",
            )
        } catch (e: Throwable) {
            Result.Failed(Result.Kind.PARSE, failText(e, "unexpected"))
        } finally {
            if (!bodyRead) runCatching { conn?.disconnect() }
        }
    }

    /**
     * A TLS 1.2 socket from the platform trust anchors. Not a trust *relaxation* — the default trust manager is
     * kept — only the protocol choice, which is what a legacy server that refuses modern renegotiation needs.
     * Built lazily and never cached across failures, because a broken SSLContext must not poison every later
     * request the app makes.
     */
    private fun tls12Factory(): javax.net.ssl.SSLSocketFactory =
        javax.net.ssl.SSLContext.getInstance("TLSv1.2").apply {
            init(null, null, null)
        }.socketFactory

    /**
     * The default trust anchors, with the legacy static-RSA suites ADDED to whatever the platform
     * already offers. The working open-source NTES client (x64vbhv/ntes-client, read 2026-10-12)
     * documents this exact server refusing everything else - "SSLV3_ALERT_HANDSHAKE_FAILURE unless
     * the cipher list is widened". Only suites the socket SUPPORTS are requested; nothing is
     * disabled, no trust decision is touched.
     */
    private fun legacyCipherFactory(): javax.net.ssl.SSLSocketFactory {
        val base = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
        return object : javax.net.ssl.SSLSocketFactory() {
            private fun prep(s: java.net.Socket): java.net.Socket = runCatching {
                (s as? javax.net.ssl.SSLSocket)?.let { sock ->
                    val wanted = arrayOf(
                        "TLS_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_RSA_WITH_AES_256_GCM_SHA384",
                        "TLS_RSA_WITH_AES_128_CBC_SHA",
                        "TLS_RSA_WITH_AES_256_CBC_SHA",
                    )
                    sock.enabledCipherSuites =
                        (sock.enabledCipherSuites + wanted.filter { it in sock.supportedCipherSuites })
                            .distinct().toTypedArray()
                }
                s
            }.getOrDefault(s)

            override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

            override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

            override fun createSocket(
                s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean,
            ): java.net.Socket = prep(base.createSocket(s, host, port, autoClose))

            override fun createSocket(host: String?, port: Int): java.net.Socket =
                prep(base.createSocket(host, port))

            override fun createSocket(
                host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int,
            ): java.net.Socket = prep(base.createSocket(host, port, localHost, localPort))

            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket =
                prep(base.createSocket(host, port))

            override fun createSocket(
                address: java.net.InetAddress?, port: Int,
                localAddress: java.net.InetAddress?, localPort: Int,
            ): java.net.Socket = prep(base.createSocket(address, port, localAddress, localPort))
        }
    }

    /**
     * One line per attempt: host and verdict. The query string is left out deliberately - a query is where a
     * train number and a travel date live, and a log the user pastes into a public issue should not become a
     * record of the trains they followed. Body size yes, body contents never; that is also why the encrypted
     * POST envelope is measured rather than quoted.
     */
    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore("/").substringBefore("?")

    /**
     * "IOException: closed" rather than "closed" - and built by concatenation rather than a template on
     * purpose. This project has twice shipped diagnostic text where a patch script's escaping leaked into the
     * source, so a literal template placeholder reached a user's screen; the string is load-bearing enough
     * should not depend on any tooling understanding it. tools/ci/check_literals.py fails the build on a leak.
     */
    private fun failText(e: Throwable, fallback: String): String =
        e.javaClass.simpleName + ": " + (e.message ?: fallback)

    /** The one failure that is a blink rather than a verdict: a name that resolved a minute ago. */
    private fun resolverBlip(r: Result): Boolean =
        r is Result.Failed && r.kind == Result.Kind.OFFLINE &&
            (r.message.contains("Unable to resolve host") || r.message.contains("UnknownHost"))

    private fun verdict(r: Result): String = when (r) {
        is Result.Ok -> "answered " + r.body.length + "B" +
            (if (r.fromCache) " from cache age=" + (r.ageMillis / 1000) + "s" else "")
        is Result.Failed -> r.kind.toString() + " " + r.message
    }

    private fun tryOnce(
        url: String,
        timeoutMillis: Int,
        headers: Map<String, String> = emptyMap(),
        pin4: Boolean = false,
        followRedirects: Boolean = true,
    ): Result = serial {
        var conn: HttpURLConnection? = null
        var bodyRead = false
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                instanceFollowRedirects = followRedirects
                requestMethod = "GET"
                // A browser-ish UA is required: several public Indian Railways endpoints reset
                // connections from clients with no User-Agent at all.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                // On the selected website a redirect is not train data, and may
                // otherwise make an invisible extra GET to a browser challenge.
                if (!followRedirects) return Result.Failed(Result.Kind.HTTP,
                    "HTTP $code redirect not followed")
                // Some other endpoints answer with a redirect to a maintenance page; follow once.
                val loc = conn.getHeaderField("Location")
                    ?: return Result.Failed(Result.Kind.HTTP, "redirect without location")
                conn.disconnect()
                conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMillis; readTimeout = timeoutMillis
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Encoding", "gzip")
                }
            }
            val responseStatus = conn.responseCode
            if (responseStatus >= 400) {
                SiteRefusalReceipt.forResponse(conn.url.host, responseStatus) { key ->
                    conn.getHeaderField(key)
                }?.let { receipt -> Diary.get()?.log("SITE_REFUSAL", receipt) }
                return Result.Failed(Result.Kind.HTTP, "HTTP $responseStatus")
            }
            val body = HttpBody.read(conn.inputStream, conn.contentEncoding)
            bodyRead = true
            if (body.isBlank()) return Result.Failed(Result.Kind.EMPTY, "empty body")
            store(url, body)
            Result.Ok(body, fromCache = false, ageMillis = 0)
        } catch (e: SocketTimeoutException) {
            Result.Failed(Result.Kind.TIMEOUT, failText(e, "timeout"))
        } catch (e: IOException) {
            val offline = e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("ENETUNREACH") == true || e.message?.contains("ENONET") == true
            Result.Failed(
                if (offline) Result.Kind.OFFLINE else Result.Kind.HTTP,
                failText(e, "io error"),
            )
        } catch (e: Throwable) {
            Result.Failed(Result.Kind.HTTP, failText(e, "unexpected"))
        } finally {
            // Only a request that never finished reading may disconnect. A completed one hands its socket back
            // to the pool on purpose; pulling the rug out from under the pool is the fault described above.
            if (!bodyRead) runCatching { conn?.disconnect() }
        }
    }

    /** An actual refusal or deliberately declined redirect is terminal. An IOException
     * classified HTTP can still recover through IPv4; a 302/403/429 cannot. */
    private fun httpStatusResponse(result: Result.Failed): Boolean =
        result.kind == Result.Kind.HTTP && result.message.matches(
            Regex("HTTP (?:3[0-9]{2} redirect not followed|[45][0-9]{2})"))

    private fun cacheFile(url: String): File =
        File(cacheRoot, Hash.sha1(url))

    private fun store(url: String, body: String) {
        runCatching {
            val f = cacheFile(url)
            f.parentFile?.mkdirs()
            // header line = fetch time; a corrupt cache file must never be fatal, hence runCatching
            f.writeText(System.currentTimeMillis().toString() + "\n" + body)
        }
    }

    private fun cached(url: String): Pair<String, Long>? {
        val f = cacheFile(url)
        if (!f.isFile) return null
        return runCatching {
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl <= 0) return null
            val ts = text.substring(0, nl).toLongOrNull() ?: return null
            val age = System.currentTimeMillis() - ts
            if (age > MAX_CACHE_AGE || age < 0) return null
            text.substring(nl + 1) to age
        }.getOrNull()
    }

    /** Drop every cached body — used by "reset data" in Settings. */
    fun clearCache() = runCatching { cacheRoot.listFiles()?.forEach { it.delete() } }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    companion object {
        private const val TAG = "Http"
        const val DEFAULT_TIMEOUT = 12_000

        /** An attempt allowed less than this is not an attempt, it is a new way to fail, so it is skipped. */
        private const val MIN_ATTEMPT_MS = 900
        private const val MAX_CACHE_AGE = 24 * 60 * 60_000L
        private const val USER_AGENT =
            "OpenTrackIndia/1.0 (offline-first railway tracker; no accounts, no tracking)"
    }
}

/** Tiny hashing helpers; MessageDigest is cheaper than pulling in a library for two uses. */
object Hash {
    fun sha1(text: String): String = digest("SHA-1", text)

    fun sha256Hex(bytes: ByteArray): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }.getOrElse { "" }

    private fun digest(algo: String, text: String): String = runCatching {
        java.security.MessageDigest.getInstance(algo).digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrElse { text.hashCode().toString(16) }
}

/** Read before closing. Closing `raw` inside a use block that returns it makes every later
 * read fail with IOException: closed, even when DNS, TLS and HTTP all succeeded. GET and POST share
 * this path so neither can reintroduce the failure independently. The reader owns and closes raw. */
internal object HttpBody {
    fun read(raw: InputStream, encoding: String?): String =
        (if ("gzip".equals(encoding, ignoreCase = true)) GZIPInputStream(raw) else raw)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
