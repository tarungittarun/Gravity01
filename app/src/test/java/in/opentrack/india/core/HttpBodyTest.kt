package `in`.opentrack.india.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/** Regression for the phone's repeated `IOException: closed` on *both* NTES POST and erail GET.
 * A prior version called `inputStream.use { it }`, closing the stream before reading its bytes.
 * The server below supplies a real HTTP body; a parser-only test would never catch this failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HttpBodyTest {
    @Test(timeout = 15_000)
    fun `GET reads the entire plain response before closing`() {
        serve("halt actuals from GET", false) { url ->
            val result = Http(RuntimeEnvironment.getApplication()).get(url, retries = 0)
            assertTrue("GET must not report IOException closed: $result", result is Http.Result.Ok)
            assertEquals("halt actuals from GET", (result as Http.Result.Ok).body)
            assertFalse(result.fromCache)
        }
    }

    @Test(timeout = 15_000)
    fun `POST reads a gzip response before closing its underlying stream`() {
        serve("halt actuals from POST", true) { url ->
            val result = Http(RuntimeEnvironment.getApplication()).post(
                url, "service=TrainRunningMob", retries = 0,
            )
            assertTrue("POST must not report IOException closed: $result", result is Http.Result.Ok)
            assertEquals("halt actuals from POST", (result as Http.Result.Ok).body)
            assertFalse(result.fromCache)
        }
    }

    private fun serve(body: String, gzip: Boolean, request: (String) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val failure = AtomicReference<Throwable?>()
            val responder: Thread = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream()
                        val header = ByteArrayOutputStream()
                        while (!header.toByteArray().endsInHeaderBreak()) {
                            val b = input.read()
                            if (b < 0 || header.size() > 8192) error("missing request headers")
                            header.write(b)
                        }
                        val length = Regex("(?im)^Content-Length: (\\d+)")
                            .find(header.toString("UTF-8"))?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(length) { if (input.read() < 0) error("short request body") }
                        val bytes = if (gzip) ByteArrayOutputStream().also { out ->
                            GZIPOutputStream(out).use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        }.toByteArray() else body.toByteArray(Charsets.UTF_8)
                        val prefix = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n" +
                            (if (gzip) "Content-Encoding: gzip\r\n" else "") +
                            "Content-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(prefix.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (e: Throwable) { failure.set(e) }
            }
            responder.start()
            try { request("http://127.0.0.1:${server.localPort}/running-status") }
            finally {
                responder.run {
                    join(11_000)
                    assertFalse("test server blocked", isAlive)
                }
                assertNull("test server failed", failure.get())
            }
        }
    }

    private fun ByteArray.endsInHeaderBreak(): Boolean = size >= 4 &&
        takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())
}
