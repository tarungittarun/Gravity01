package `in`.opentrack.india.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The properties that make the collector safe to ship in an app whose whole promise is "no analytics":
 * nothing is recorded while it is off, the contents are bounded, and nothing that looks like a credential or
 * a digest survives the redaction pass. The rotation rule gets a test because a log that silently eats its own
 * tail would destroy the one capture the user waited twenty minutes to produce.
 */
class DiaryTest {

    private fun tmp(): File = File.createTempFile("opentrack-diary", ".txt").apply { delete() }

    @Test
    fun `a disabled diary records nothing at all`() {
        val file = tmp()
        val diary = Diary(file)
        repeat(50) { diary.log("HTTP", "GET example.invalid try#1 -> answered 12B") }
        assertFalse("a file was created although collection was never enabled", file.exists())
        assertFalse(
            "the shareable text holds entries although collection was off",
            diary.text().contains("example.invalid"),
        )
        assertFalse(diary.enabled)
        assertNullish(diary.file())
    }

    @Test
    fun `enabling writes through to the file and the shareable text`() {
        val file = tmp()
        val diary = Diary(file)
        diary.setEnabled(true)
        diary.log("HTTP", "GET enquiry.indianrail.gov.in try#1 -> HTTP IOException: closed")
        diary.log("PROBE", "post-tls12: answered 4021B, usable running data")
        // The first line is written inline and the rest on the writer thread. awaitIdle is the durability
        // barrier the class offers, so this asserts on the contract instead of on how long a sleep is enough
        // - and the assertion below is the one that failed on CI when this test raced its own writer.
        assertTrue("queued writes never drained", diary.awaitIdle())
        val text = if (file.exists()) file.readText() else ""
        assertTrue("the file never received the queued line: '$text'", text.contains("post-tls12"))
        assertTrue(text.contains("IOException: closed"))
        val shared = diary.text()
        assertTrue(shared.startsWith("# TrackTrain diagnostic log"))
        assertTrue(shared.contains("[HTTP]"))
        diary.disable()
        assertFalse(diary.enabled)
        assertTrue("the footer never drained", diary.awaitIdle())
        val afterDisable = file.readText()
        diary.log("HTTP", "GET hidden.invalid")
        assertEquals("a line written after disable reached the disk", afterDisable, file.readText())
        assertFalse(diary.text().contains("hidden.invalid"))
    }

    @Test
    fun `credentials and digests never survive sanitisation`() {
        val diary = Diary(tmp())
        val noisy = diary.sanitize(
            "POST body={\"jsonIn\":\"8EA4DB2CC1EB3DC57DC5EB3BB4DB6EA8#deadbeef\"} " +
                "app_key=abc123DEF456 &token=\"t-9999\" secret=hidden password: 'p@ss' " +
                "multi\nline\npayload",
        )
        assertFalse("a bare 32-hex digest leaked: $noisy", noisy.contains("8EA4DB2CC1EB3DC57DC5EB3BB4DB6EA8"))
        assertFalse("app_key leaked: $noisy", noisy.contains("abc123DEF456"))
        assertFalse("token leaked: $noisy", noisy.contains("t-9999"))
        assertFalse("secret leaked: $noisy", noisy.contains("hidden"))
        assertFalse("password leaked: $noisy", noisy.contains("p@ss"))
        assertFalse("a log line must not span lines: $noisy", noisy.contains("\n"))
        assertTrue(noisy.contains("<redacted"))
        val long = diary.sanitize("x".repeat(5_000))
        assertTrue("long lines must be cut, was ${long.length}", long.length < 1100)
        assertTrue(long.contains("…(+" + (5_000 - 1000) + "B)"))
    }

    @Test
    fun `rotation keeps the tail and says it happened`() {
        val diary = Diary(tmp())
        val small = ByteArray(10)
        assertNullish(diary.rotationPlan(small, 2_000_000L))
        val bytes = ByteArray(4_000) { (it % 26 + 'a'.code).toByte() }
        val plan = diary.rotationPlan(bytes, 2_000L)!!
        assertTrue(plan.first.contains("older half"))
        assertTrue(plan.first.contains("4000B"))
        assertTrue(plan.first.contains("the tail is intact"))
        assertEquals(2_000, plan.second.size)
        // the kept half really is the *newest* half
        assertEquals(String(bytes, 2_000, 2_000, Charsets.UTF_8), String(plan.second, Charsets.UTF_8))
    }

    @Test
    fun `shared text is capped so a share cannot be a denial of service`() {
        val diary = Diary(tmp())
        diary.setEnabled(true)
        diary.log("HTTP", "FIRST-entry-should-be-evicted")
        repeat(3_000) { diary.log("HTTP", "GET example.invalid try#1 -> answered " + "y".repeat(300) + "B") }
        val body = diary.text()
        assertFalse("the ring must be bounded: a burst cannot pin memory", body.contains("FIRST-entry"))
        assertTrue("the newest entry must survive the cap", body.contains("y".repeat(300)))
        assertTrue("truncation must be stated, not silent", body.contains("omitted"))
    }

    private fun assertNullish(value: Any?) {
        assertTrue("expected null or empty, got '$value'", value == null)
    }
}
