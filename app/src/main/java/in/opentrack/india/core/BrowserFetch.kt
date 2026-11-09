package `in`.opentrack.india.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * A request sent with the browser engine's network stack, with the answer read back as **bytes**.
 *
 * Why the app has one at all: on the phone this project is developed against, `HttpURLConnection` opens the
 * socket to the railway enquiry endpoint in ~100 ms and then has every TLS handshake hung up on -
 * `IOException: closed`, across eleven request shapes and both TLS profiles - while Chrome on the same phone,
 * same Wi-Fi, same minute loads the same site. The difference is the client: the browser's fingerprint, ALPN
 * set and header order are examined at the far end, and this app's platform stack does not survive that. Using
 * the engine that is already installed costs no key, no proxy of ours, and no sign-up.
 *
 * Why it is no longer a *renderer*. The previous version of this file asked the engine to `loadUrl`/`postUrl`
 * the API directly and read `document.body.innerText` back. That is what produced the line that made me
 * believe the endpoint "answered 159 bytes" from a phone in Bengaluru. Those 159 bytes were:
 *
 *     This page contains the following errors: error on line 1 at column 1: Start tag expected, '<' not found
 *     Below is a rendering of the page up to the fir
 *
 * - Android's own message for "the server sent me something that is not markup". The response body never
 * reached us: a JSON answer cannot be rendered, so the parser error *became* the document, truncated at
 * column 1, exactly as the wording promises. A GET of the same API through the same renderer returned 506
 * bytes of the NTES HTML page for the same reason: the site answers a mis-called endpoint with its own page.
 * Two consequences, both acted on here: an answer read this way can never be parsed into live data, and it
 * must never be reported as if it had been.
 *
 * So the request now rides *inside* a page. The engine loads a same-origin document - any of them, including
 * the site's own 404 - and from that document this code runs `fetch()` on the API and reads the returned text
 * out of a JavaScript global. Same origin, so the site's cookies and its `Referer` are there; a real
 * `Response` object, so the HTTP status and `Content-Type` are known for the first time; and no parser in the
 * middle, so the bytes are the server's, not the renderer's. It is, in other words, the request the NTES
 * website makes for itself - which is the one request shape this app has never been able to produce, because
 * it needs an origin.
 *
 * What this is NOT: a browser, a web view in the UI, or a way for a page to reach into the app.
 *
 *  - The view is never attached to a window and never shown; the traveller sees the same screens as before.
 *  - There is no `addJavascriptInterface`, so no document - not even a hostile one served in place of the
 *    API - can call into this process. The only script run here is the three-line `fetch` above, and the only
 *    thing read back is one global this code names.
 *  - File, content, database and cache access are off; nothing is persisted by us. The engine's own cookie jar
 *    stays in the app's data directory, which is what makes a session cookie possible, and it is cleared by the
 *    same button in Settings that clears the live cache.
 *  - Only railway endpoints are ever loaded: [originPage] derives the ride-along page from the API URL itself.
 *
 * Threading: `WebView` insists on the main looper, so the work is posted there and awaited on the caller's (an
 * IO) thread. The main thread is never blocked - the latch is on the other side - and a call that outlives its
 * budget tears the engine down, so one stalled page cannot poison the next refresh.
 */
object BrowserFetch {

    @Volatile
    private var view: WebView? = null

    @Volatile
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())
    private val creating = Any()

    /**
     * One call at a time, for the whole engine. The view is shared and a call replaces its `WebViewClient`, so
     * two requests in flight - a refresh and the diagnostic probe, which run on different threads - would
     * finish in each other's callbacks. The `EMPTY browser answered with an empty document` line in a device
     * report is that race, not a server: the second caller found a document the first one had already read.
     * Browser calls here are rare and few, so serialising costs nothing the traveller can feel.
     */
    private val gate = Any()

    /** A cold engine, a page load and a request: measured at 3.9 s for a GET on a device, so 5 s was too tight. */
    val defaultTimeout = 13_000

    /** True once a fetch has been attempted, so a report can tell "not tried" from "tried and refused". */
    @Volatile
    var tried: Boolean = false
        private set

    // What the loaded page's own scripts asked for, bounded - the honest way to learn an endpoint:
    // watch the site, never invent its URLs (two folk-lore spellings already measured 404 from a
    // phone that passes the WAF; the third spelling will not be guessed here).
    private val observed = java.util.Collections.synchronizedList(ArrayList<String>())

    fun observedRequestsSnapshot(): List<String> = synchronized(observed) { observed.toList() }

    fun noteObserved(url: String) {
        if (observed.size < 24) observed.add(url.take(130))
    }

    @Volatile
    var lastError: String? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The document a same-origin request is sent from. Derived from the API URL rather than configured, so
     * there is no way for this to end up fetching through an unrelated site's session.
     */
    fun originPage(url: String): String = "https://" + hostOf(url) + "/"

    internal fun hostOf(url: String): String = url.substringAfter("://", "").substringBefore("/")

    /**
     * One API call, as the site itself would make it. [page] can be pointed at a deeper URL when the point of
     * the question is the session cookie the site's own scripts receive.
     */
    fun apiCall(
        url: String,
        body: String?,
        page: String = originPage(url),
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = defaultTimeout,
    ): Http.Result {
        val ctx = appContext ?: return Http.Result.Failed(
            Http.Result.Kind.HTTP, "BrowserFetch not initialised (no application context)",
        )
        tried = true
        synchronized(observed) { observed.clear() }
        val call = Call(url, body, page, headers)
        val outcome = synchronized(gate) {
            val t0 = System.currentTimeMillis()
            runCatching { main.post { call.start(ctx) } }
                .onFailure { call.note("main-looper unavailable: " + it.javaClass.simpleName) }
            val answered = call.done.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            if (!answered) abandon()
            // The elapsed time is measured inside the lock on purpose: a call that queued behind another one
            // was slow for the traveller too, and reporting only its own flight time is how a 41-second
            // refresh got logged as a 10-second timeout.
            call.verdict(answered, timeoutMillis) to (System.currentTimeMillis() - t0)
        }
        val verdict = outcome.first
        val ms = outcome.second
        val how = when (verdict) {
            is Http.Result.Ok -> "body=" + verdict.body.length + "B"
            is Http.Result.Failed -> verdict.kind.toString() + " " + verdict.message
        }
        // The first 180 characters of a *successful* body, on the same line. Their last log had the sentence
        // "this build could not open its envelope" and no way to say why: a refusal inside a 200 - an
        // `AlertMsg`, a login redirect, an HTML page where JSON was promised - is the whole next round of
        // work, and it was not recorded. Nothing user-generated is in here, only what a public endpoint said.
        val peek = if (verdict is Http.Result.Ok) " :: " + verdict.body.take(180).replace('\n', ' ') else ""
        Diary.get()?.log("BROWSER") {
            // Status and content type on the line, because that pair is the whole reason this file was
            // rewritten: without them, "answered 159B" looked like a verdict for a round.
            (if (body == null) "GET " else "POST ") + hostOf(url) + " via " + hostOf(page) +
                " -> HTTP " + call.status.get() + " " + call.contentType.get() + " " + how + peek +
                " in " + ms + "ms"
        }
        lastError = (verdict as? Http.Result.Failed)?.message
        return verdict
    }

    /**
     * Load a real page in the engine and read its rendered text - the one capability left for a site
     * whose live data arrives over its own websocket (erail/SignalR, measured 2026-10-12): their page
     * populates itself, and what any browser user reads, this reads. Not the apiCall path (JSON bytes
     * cannot render - that mistake is documented at the top of this file); here rendering IS the point.
     * Same safety envelope as every call here: offscreen view, no JS interface, one call at a time.
     */
    fun pageText(
        url: String,
        timeoutMillis: Int = defaultTimeout,
        /**
         * Wait this long after the page reports "finished" BEFORE reading its text. erail's running
         * table is filled by their own scripts seconds after the page settles (the owner's paste of
         * 2026-10-12: the instant read caught 426 B of navigation while the halt table was still
         * mid-flight). Zero keeps the old instant read.
         */
        settleMillis: Int = 0,
        /** innerText cap; 1200 was a navigation-menu read, the running table needs room. */
        textCap: Int = 1200,
        /** Read the page as raw HTML (outerHTML) instead of rendered text - data can sit in the DOM invisibly. */
        dom: Boolean = false,
        /** After the settle wait, click the first element whose text equals this (case-insensitive), then re-wait. */
        tapText: String = "",
        /** How long after the tap to wait before reading. */
        tapSettleMillis: Int = 6_000,
        /** Present as desktop Chrome: some sites serve a different - and differently populated - page. */
        desktop: Boolean = false,
    ): Http.Result {
        val ctx = appContext ?: return Http.Result.Failed(
            Http.Result.Kind.HTTP, "BrowserFetch not initialised (no application context)",
        )
        tried = true
        val latch = CountDownLatch(1)
        val settled = AtomicBoolean(false)
        val text = AtomicReference<String?>(null)
        val problem = AtomicReference<String?>(null)
        synchronized(gate) {
            val t0 = System.currentTimeMillis()
            runCatching {
                main.post {
                    runCatching {
                        val w = ensureView(ctx)
                        if (desktop) {
                            w.settings.userAgentString = DESKTOP_UA
                            Diary.get()?.log("BROWSER") { "UA switched to desktop for this read" }
                        }
                        w.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(v: WebView, pageUrl: String) {
                                if (settled.get()) return
                                fun readNow() {
                                    val expr = (if (dom) "document.documentElement.outerHTML"
                                        else "(document.body?document.body.innerText:'')") +
                                        ".substring(0,$textCap)"
                                    runCatching {
                                        v.evaluateJavascript(
                                            expr,
                                        ) { raw ->
                                            if (settled.compareAndSet(false, true)) {
                                                runCatching {
                                                    val quoted = raw ?: "''"
                                                    text.set(JSONObject("{\"v\":$quoted}").optString("v"))
                                                }.onFailure { problem.set("unreadable text: " + it.javaClass.simpleName) }
                                                latch.countDown()
                                            }
                                        }
                                    }.onFailure {
                                        if (settled.compareAndSet(false, true)) {
                                            problem.set(it.message ?: it.javaClass.simpleName)
                                            latch.countDown()
                                        }
                                    }
                                }
                                if (tapText.isNotEmpty()) {
                                    main.postDelayed({
                                        if (settled.get()) return@postDelayed
                                        val lit = JSONObject.quote(tapText)
                                        v.evaluateJavascript(
                                            "(function(){var w=[$lit].map(function(s){return s.toLowerCase();});" +
                                                "var els=document.querySelectorAll('a,button,div,span,li,td');" +
                                                "for(var i=0;i<els.length;i++){var t=(els[i].textContent||'');" +
                                                "if(w.indexOf(t.trim().toLowerCase())>=0){els[i].click();" +
                                                "return 'tapped';}}return 'no-target';})()",
                                        ) { res ->
                                            Diary.get()?.log("BROWSER") {
                                                "TAP " + hostOf(url) + " -> " + (res ?: "null") +
                                                    " (waiting " + tapSettleMillis + "ms, then reading)"
                                            }
                                            main.postDelayed(
                                                { if (!settled.get()) readNow() },
                                                tapSettleMillis.toLong(),
                                            )
                                        }
                                    }, settleMillis.coerceAtLeast(0).toLong())
                                } else if (settleMillis > 0) {
                                    main.postDelayed({ if (!settled.get()) readNow() }, settleMillis.toLong())
                                } else {
                                    readNow()
                                }
                            }

                            override fun onReceivedError(
                                v: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                // A 404 page is still a rendered document from the right site; read it
                                // rather than declaring the site unreachable (the apiCall rule, restated).
                            }
                        }
                        w.loadUrl(url)
                    }.onFailure {
                        if (settled.compareAndSet(false, true)) {
                            problem.set(it.message ?: it.javaClass.simpleName)
                            latch.countDown()
                        }
                    }
                }
            }.onFailure {
                if (settled.compareAndSet(false, true)) {
                    problem.set(it.message ?: it.javaClass.simpleName)
                    latch.countDown()
                }
            }
            val answered = latch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            if (!answered) abandon()
            val ms = System.currentTimeMillis() - t0
            val body = text.get()
            Diary.get()?.log("BROWSER") {
                (if (dom) "DOM " else "PAGE ") + hostOf(url) + url.substringAfter(hostOf(url), "") +
                    " -> " + (if (answered) "text ${body?.length ?: 0}B" else "TIMEOUT") + " in " + ms + "ms"
            }
            return when {
                !answered -> Http.Result.Failed(
                    Http.Result.Kind.TIMEOUT, "the page gave no text in " + timeoutMillis + "ms",
                )
                body != null -> Http.Result.Ok(body, fromCache = false, ageMillis = 0)
                else -> Http.Result.Failed(Http.Result.Kind.HTTP, problem.get() ?: "unreadable page")
            }
        }
    }

    /**
     * The interactive page probe: settle, click each named control in order (a date chip, then a
     * submit button - the sequence a human runs), wait for their slow backend, then read the parent
     * page's text PLUS any same-origin iframes' text. Built after the owner's 16:33 paste proved the
     * tap FIRED ("tapped") while the DOM came back byte-identical - the table either needs longer
     * than the 6 s we waited, or paints somewhere the parent document does not expose. Every step
     * logs, so the paste says which link in the chain broke.
     */
    fun pageInteract(
        url: String,
        settleMillis: Int,
        taps: List<String>,
        postTapWaitMillis: Int,
        totalBudgetMillis: Int,
    ): Http.Result {
        val ctx = appContext ?: return Http.Result.Failed(
            Http.Result.Kind.HTTP, "BrowserFetch not initialised (no application context)",
        )
        tried = true
        val latch = CountDownLatch(1)
        val settled = AtomicBoolean(false)
        val text = AtomicReference<String?>(null)
        val problem = AtomicReference<String?>(null)
        synchronized(gate) {
            val t0 = System.currentTimeMillis()
            runCatching {
                main.post {
                    runCatching {
                        val w = ensureView(ctx)
                        w.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(v: WebView, pageUrl: String) {
                                if (settled.get()) return
                                fun readAll() {
                                    if (settled.get()) return
                                    runCatching {
                                        v.evaluateJavascript("location.href", ) { loc ->
                                            Diary.get()?.log("BROWSER") {
                                                "AFTER-TAPS page is at " + (loc ?: "?")
                                            }
                                        }
                                        val expr = "(function(){var out=(document.body?document.body.innerText:'');" +
                                            "var f=document.querySelectorAll('iframe');" +
                                            "for(var i=0;i<f.length&&i<3;i++){" +
                                            "try{var d=f[i].contentDocument;" +
                                            "if(d&&d.body){out=out+' ||IFRAME['+i+']|| '+((d.body.innerText||'').substring(0,4000));}" +
                                            "else{out=out+' ||IFRAME['+i+']|| unreachable';}}" +
                                            "catch(e){out=out+' ||IFRAME['+i+']|| cross-origin';}}" +
                                            "return out.substring(0,24000);})()"
                                        v.evaluateJavascript(expr) { raw ->
                                            if (settled.compareAndSet(false, true)) {
                                                runCatching {
                                                    val quoted = raw ?: "''"
                                                    text.set(JSONObject("{\"v\":$quoted}").optString("v"))
                                                }.onFailure { problem.set("unreadable text: " + it.javaClass.simpleName) }
                                                latch.countDown()
                                            }
                                        }
                                    }.onFailure {
                                        if (settled.compareAndSet(false, true)) {
                                            problem.set(it.message ?: it.javaClass.simpleName)
                                            latch.countDown()
                                        }
                                    }
                                }
                                fun tapAt(index: Int) {
                                    if (settled.get()) return
                                    if (index >= taps.size) {
                                        main.postDelayed(
                                            { if (!settled.get()) readAll() },
                                            postTapWaitMillis.toLong(),
                                        )
                                        return
                                    }
                                    val label = taps[index]
                                    val lit = JSONObject.quote(label)
                                    // normalized equality: "12-Oct", "12 Oct" and "12oct" are one target
                                    val js = "(function(){var norm=function(s){return s.toLowerCase()" +
                                        ".replace(/[^a-z0-9]/g,'');};" +
                                        "var want=[$lit].map(function(s){return norm(s);});" +
                                        "var els=document.querySelectorAll('a,button,div,span,li,td,input');" +
                                        "for(var i=0;i<els.length;i++){var t=norm((els[i].value||'')+' '+(els[i].textContent||''));" +
                                        "if(want.some(function(w){return t===w;})){" +
                                        "els[i].click();return 'tapped';}}return 'no-target';})()"
                                    v.evaluateJavascript(js) { res ->
                                        Diary.get()?.log("BROWSER") {
                                            "TAP[$label] -> " + (res ?: "null")
                                        }
                                        main.postDelayed(
                                            { runCatching { tapAt(index + 1) } },
                                            400L,
                                        )
                                    }
                                }
                                main.postDelayed({ if (!settled.get()) runCatching { tapAt(0) } }, settleMillis.toLong())
                            }

                            override fun onReceivedError(
                                v: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                // A 404 page is still a rendered document from the right site; read it
                                // rather than declaring the site unreachable (the apiCall rule, restated).
                            }
                        }
                        w.loadUrl(url)
                    }.onFailure {
                        if (settled.compareAndSet(false, true)) {
                            problem.set(it.message ?: it.javaClass.simpleName)
                            latch.countDown()
                        }
                    }
                }
            }.onFailure {
                if (settled.compareAndSet(false, true)) {
                    problem.set(it.message ?: it.javaClass.simpleName)
                    latch.countDown()
                }
            }
            val answered = latch.await(totalBudgetMillis.toLong(), TimeUnit.MILLISECONDS)
            if (!answered) abandon()
            val ms = System.currentTimeMillis() - t0
            val body = text.get()
            Diary.get()?.log("BROWSER") {
                "INTERACT " + hostOf(url) + url.substringAfter(hostOf(url), "") +
                    " -> " + (if (answered) "text ${body?.length ?: 0}B" else "TIMEOUT") + " in " + ms + "ms"
            }
            return when {
                !answered -> Http.Result.Failed(
                    Http.Result.Kind.TIMEOUT, "the page gave no text in " + totalBudgetMillis + "ms",
                )
                body != null -> Http.Result.Ok(body, fromCache = false, ageMillis = 0)
                else -> Http.Result.Failed(Http.Result.Kind.HTTP, problem.get() ?: "unreadable page")
            }
        }
    }

    /** Clears the engine's cookie jar; wired to the same button that clears the app's live cache. */
    fun clearCookies() {
        val ctx = appContext ?: return
        runCatching {
            main.post {
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
                view?.let { dead ->
                    view = null
                    runCatching { dead.loadUrl("about:blank") }
                    runCatching { dead.destroy() }
                }
            }
        }
        Diary.get()?.log("BROWSER", "cookies cleared for ctx=" + ctx.javaClass.simpleName)
    }

    /** A load that never reported back is not reusable: drop the engine so the next attempt starts clean. */
    private fun abandon() {
        val dead = view ?: return
        view = null
        main.post {
            runCatching { dead.stopLoading() }
            runCatching { dead.loadUrl("about:blank") }
            runCatching { dead.destroy() }
        }
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    private fun ensureView(ctx: Context): WebView {
        view?.let { return it }
        synchronized(creating) {
            view?.let { return it }
            val w = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = false
                setBackgroundColor(0)
            }
            view = w
            return w
        }
    }

    /**
     * One call: load the page, run the fetch, poll for the global, decode twice.
     *
     * The double decode is not ceremony. `evaluateJavascript` hands back the *source text* of the JS value, so
     * a string global arrives quoted and escaped; that literal is decoded once to recover the JSON this script
     * built, and the JSON is decoded again for the fields. Anything else - a page that replaced the global with
     * something unparseable, an error page that threw - ends in [note], never in a splice.
     */
    private class Call(
        val url: String,
        val body: String?,
        val page: String,
        val headers: Map<String, String>,
    ) {
        val done = CountDownLatch(1)
        private val settled = AtomicBoolean(false)
        private val text = AtomicReference<String?>(null)
        val status = AtomicInteger(-1)
        val contentType = AtomicReference<String>("unknown")
        private val problem = AtomicReference<String?>(null)
        private val started = AtomicBoolean(false)
        private val tries = AtomicInteger(0)

        fun start(ctx: Context) {
            runCatching {
                val w = BrowserFetch.ensureView(ctx)
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, pageUrl: String) {
                        send(v)
                    }

                    override fun shouldInterceptRequest(
                        v: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (!request.isForMainFrame) BrowserFetch.noteObserved(request.url.toString())
                        return null
                    }

                    override fun onReceivedError(
                        v: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (!request.isForMainFrame) return
                        // Do not give up here. A page that answers 404 or 403 is still a document from the
                        // right origin, which is all this needs: the fetch runs from it and carries the
                        // cookies and Referer of that origin. Abandoning on a page error is how a
                        // "reachable, refused" endpoint gets reported as "unreachable" - and the railway's
                        // root path is exactly such a page.
                        send(v)
                    }
                }
                w.loadUrl(page)
            }.onFailure { note(it.javaClass.simpleName + ": " + it.message) }
        }

        private fun send(v: WebView) {
            if (!started.compareAndSet(false, true)) return
            runCatching {
                v.evaluateJavascript(script(), null)
                BrowserFetch.main.postDelayed({ read(v) }, BrowserFetch.POLL_MS)
            }.onFailure { note("javascript unavailable: " + it.javaClass.simpleName) }
        }

        private fun read(v: WebView) {
            if (settled.get()) return
            runCatching {
                v.evaluateJavascript("window.__otr || ''") { raw ->
                    runCatching {
                        val wrapped = JSONObject("{\"v\":" + (raw ?: "''") + "}").optString("v")
                        if (wrapped.isEmpty()) {
                            if (tries.incrementAndGet() < BrowserFetch.MAX_POLLS && !settled.get()) {
                                BrowserFetch.main.postDelayed({ read(v) }, BrowserFetch.POLL_MS)
                            } else {
                                note("the page accepted the request and the answer never arrived")
                            }
                        } else {
                            val o = JSONObject(wrapped)
                            status.set(o.optInt("st", -1))
                            contentType.set(o.optString("ty", "unknown"))
                            val got = o.optString("bd", "")
                            text.set(got)
                            if (status.get() == 0) {
                                note("the browser refused the same-origin request: " + got.take(160))
                            } else {
                                settled.set(true)
                                done.countDown()
                            }
                        }
                    }.onFailure { note("unreadable answer: " + it.javaClass.simpleName) }
                }
            }.onFailure { note("evaluateJavascript failed: " + it.javaClass.simpleName) }
        }

        fun note(message: String) {
            if (settled.compareAndSet(false, true)) {
                problem.set(message)
                runCatching { done.countDown() }
            }
        }

        fun verdict(answered: Boolean, timeoutMillis: Int): Http.Result {
            if (!answered) {
                return Http.Result.Failed(
                    Http.Result.Kind.TIMEOUT,
                    "the browser gave no answer in " + timeoutMillis + "ms (page "
                        + BrowserFetch.hostOf(page) + ")",
                )
            }
            problem.get()?.let { return Http.Result.Failed(Http.Result.Kind.HTTP, it) }
            val got = text.get().orEmpty()
            val label = "HTTP " + status.get() + " " + contentType.get()
            if (status.get() in 200..299) {
                if (got.isBlank()) {
                    return Http.Result.Failed(Http.Result.Kind.EMPTY, label + " with an empty body")
                }
                return Http.Result.Ok(got, fromCache = false, ageMillis = 0)
            }
            return Http.Result.Failed(
                Http.Result.Kind.HTTP,
                label + (if (got.isBlank()) "" else " " + got.take(200).replace('\n', ' ')),
            )
        }

        /**
         * The script, with the request described by a JSON literal instead of interpolated strings: an endpoint
         * URL, a header value or an envelope that contains a quote cannot then break the script or reach a
         * second statement, which is the difference between "read the API" and "run whatever a response told
         * us to run".
         */
        private fun script(): String {
            val spec = JSONObject()
            spec.put("u", url)
            spec.put("m", if (body == null) "GET" else "POST")
            spec.put("b", body ?: JSONObject.NULL)
            val h = JSONObject()
            for ((k, value) in headers) h.put(k, value)
            spec.put("h", h)
            // JSONObject escapes quotes and control characters but not the two line terminators JSON allows
            // and JavaScript source does not, so those are encoded by hand.
            val literal = spec.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            return "(function(){window.__otr='';var s=" + literal + ";" +
                "fetch(s.u,{method:s.m,body:s.b,headers:s.h,credentials:'include',cache:'no-store'})" +
                ".then(function(r){return r.text().then(function(t){window.__otr=JSON.stringify(" +
                "{st:r.status,ty:(r.headers.get('content-type')||''),bd:t.slice(0,200000))})})" +
                ".catch(function(e){window.__otr=JSON.stringify({st:0,ty:'fetch-refused'," +
                "bd:String((e&&e.name)||e)})})})()"
        }
    }

    private const val POLL_MS = 150L

    /** 6 s of polling, which on this network is far more than a same-origin fetch on a loaded page takes. */
    private const val MAX_POLLS = 40
}
