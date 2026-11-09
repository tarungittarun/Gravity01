package `in`.opentrack.india.v2.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLHandshakeException

/**
 * The real transport, with the connection factory injected: every failure path is driven by a fake that
 * throws what the network actually throws, and the assertion is that the failure comes back TYPED -
 * "could not look" with a reason - never an exception up the stack, never an invented empty answer.
 */
class ErailTransportTest {

    private val query = PairQuery("SMET", "MYS", budgetMs = 12_000)

    @Test
    fun aTimeoutIsTypedAsTimeout() {
        val transport = ErailTransport({ throw SocketTimeoutException("connect timed out") })
        val wire = transport.fetch(query)
        assertTrue(wire is PairWire.Unreachable)
        assertEquals(PairWireFailure.TIMEOUT, (wire as PairWire.Unreachable).failure)
    }

    @Test
    fun aTlsFailureIsTypedAsTls() {
        // The measured runner-side reality for NTES, and the possible one for any host: the handshake
        // dies. The type is the difference between "their site is slow" and "someone is in the middle".
        val transport = ErailTransport({ throw SSLHandshakeException("handshake timed out") })
        val wire = transport.fetch(query)
        assertEquals(PairWireFailure.TLS, (wire as PairWire.Unreachable).failure)
    }

    @Test
    fun anyOtherIoFailureIsNetwork() {
        val transport = ErailTransport({ throw IOException("Connection reset") })
        val wire = transport.fetch(query)
        assertEquals(PairWireFailure.NETWORK, (wire as PairWire.Unreachable).failure)
    }

    @Test
    fun evenARuntimeExceptionComesBackTypedNotThrown() {
        val transport = ErailTransport({ throw IllegalStateException("pool drained") })
        val wire = transport.fetch(query)
        assertTrue(wire is PairWire.Unreachable)
        assertTrue((wire as PairWire.Unreachable).detail.contains("IllegalStateException"))
    }

    @Test
    fun thePairUrlIsTheLadderCandidateThatAnswered() {
        // The exact query shape the CI probe ladder proved (first candidate, HTTP 200, 2026-10-12).
        assertEquals(
            "https://erail.in/rail/getTrains.aspx?Station_From=SMET&Station_To=MYS" +
                "&Trains=BOTH&Sort_Type=PVT&Language=0&DataSource=1",
            ErailTransport.pairUrl("SMET", "MYS"),
        )
    }

    @Test
    fun theUrlIsHttpsWithinTheBudget() {
        val url = URL(ErailTransport.pairUrl("SMET", "MYS"))
        assertEquals("https", url.protocol)
        assertTrue(query.budgetMs in 1..20_000)
    }
}
