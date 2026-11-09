package `in`.opentrack.india.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The selected independent website's document navigation through Android's actual WebView engine.
 * This is NOT a forged Chrome header set, Google's WIMT phone app, or a way to solve a challenge.
 * WebView has its own browser version and network/session identity; a 403 remains a refusal.
 * The only source of accepted data is this very document's DOM after a successful main-frame load.
 */
object WebsiteBrowserRequest {
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private val allowed = Regex("https://whereismytrain\\.org\\.in/\\?trainNo=[0-9]{5}")
    private const val TIMEOUT_MS = 12_000L
    private const val MAX_HTML = 1_000_000

    fun document(context: Context, url: String): Http.Result {
        if (!allowed.matches(url)) return Http.Result.Failed(Http.Result.Kind.HTTP, "invalid website URL")
        if (Looper.myLooper() == Looper.getMainLooper())
            return Http.Result.Failed(Http.Result.Kind.HTTP, "browser document must run off the UI thread")
        synchronized(gate) {
            val pending = Pending(context.applicationContext, url)
            if (!main.post { pending.start() })
                return Http.Result.Failed(Http.Result.Kind.HTTP, "browser looper unavailable")
            val completed = pending.done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) pending.abort()
            val answer = if (completed) pending.result
                else Http.Result.Failed(Http.Result.Kind.TIMEOUT, "browser document timed out")
            // Only the main-frame navigation is counted: normal browser resource GETs may also occur.
            // Never log body, URL query, cookies, challenge tokens or raw CDN request IDs.
            if (pending.navigated) Diary.get()?.log("HTTP") {
                "GET whereismytrain.org.in try#1 -> " + when (answer) {
                    is Http.Result.Ok -> "answered ${answer.body.length}B (WebView document)"
                    is Http.Result.Failed -> "${answer.kind} ${answer.message} (WebView document)"
                }
            }
            return answer
        }
    }

    private class Pending(val context: Context, val url: String) {
        val done = CountDownLatch(1)
        private val finished = AtomicBoolean(false)
        @Volatile var result: Http.Result = Http.Result.Failed(Http.Result.Kind.EMPTY, "no document")
            private set
        private var view: WebView? = null // main looper only
        @Volatile var navigated: Boolean = false
            private set

        fun abort() {
            if (finished.compareAndSet(false, true)) main.post { close() }
        }

        private fun close() {
            view?.let {
                view = null
                runCatching { it.stopLoading() }
                runCatching { it.destroy() }
            }
        }

        private fun finish(value: Http.Result) {
            if (!finished.compareAndSet(false, true)) return
            result = value
            done.countDown()
            // Do not destroy the engine from inside one of its own navigation callbacks.
            main.post { close() }
        }

        fun start() {
            if (finished.get()) return
            try {
                val browser = WebView(context)
                view = browser
                browser.settings.apply {
                    // Standard WebView page capabilities, NOT proof that Hostinger will admit us.
                    // No forged UA, copied Chrome cookies, JS bridge or challenge solver.
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = true
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    // The document and its CDN are first-party on this host; do not
                    // allow unrelated third-party tracking cookies without a need.
                    setAcceptThirdPartyCookies(browser, false)
                }
                browser.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame) {
                            finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                                "website redirected or navigated; no train document"))
                            return true
                        }
                        return false
                    }

                    override fun onReceivedHttpError(v: WebView, request: WebResourceRequest,
                                                     response: WebResourceResponse) {
                        if (!request.isForMainFrame) return // an image's 403 is not the train page's 403
                        if (response.statusCode == 403) {
                            // Capture the exact header before finishing the failed navigation.
                            // This is private, opt-in device evidence; it is never in the UI,
                            // result message, public CI comment or uploaded device artifact.
                            val id = WebsiteRefusalMetadata.header(response.responseHeaders)
                            Diary.get()?.logWebsiteRefusal(request.url.toString(), id,
                                System.currentTimeMillis())
                        }
                        finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                            "HTTP ${response.statusCode}"))
                    }

                    override fun onReceivedError(v: WebView, request: WebResourceRequest,
                                                 error: WebResourceError) {
                        if (request.isForMainFrame) finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                            "browser network error ${error.errorCode}"))
                    }

                    override fun onPageFinished(v: WebView, pageUrl: String) {
                        if (finished.get()) return
                        if (pageUrl != url) {
                            finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                                "website document URL changed; no train data"))
                            return
                        }
                        // Read the rendered document, never an unrelated page or browser error page.
                        v.evaluateJavascript("(function(){var h=document.documentElement.outerHTML;" +
                            "return h.length<=${MAX_HTML}?h:'';})()") { encoded ->
                            if (finished.get()) return@evaluateJavascript
                            val html = runCatching {
                                JSONObject("{\"html\":${encoded ?: "null"}}")
                                    .optString("html")
                            }.getOrDefault("")
                            if (html.isBlank()) finish(Http.Result.Failed(Http.Result.Kind.EMPTY,
                                "browser document empty or oversized"))
                            else finish(Http.Result.Ok(html, fromCache = false, ageMillis = 0))
                        }
                    }
                }
                navigated = true
                browser.loadUrl(url)
            } catch (error: Exception) {
                finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                    "browser unavailable: ${error.javaClass.simpleName}"))
            }
        }
    }
}
