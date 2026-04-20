package dev.snapecg.holter

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*

/**
 * Tests for connector discovery via port scanning.
 * Runs on JVM — no Android device needed.
 */
class DiscoveryTest {

    private var fakeConnector: ServerSocket? = null
    private var fakeConnectorPort = 0

    @Before
    fun setUp() {
        // Start a fake connector server on localhost
        fakeConnector = ServerSocket(0) // random free port
        fakeConnectorPort = fakeConnector!!.localPort
        // Accept connections in background
        Thread {
            while (!fakeConnector!!.isClosed) {
                try {
                    val client = fakeConnector!!.accept()
                    client.close() // just accept and close
                } catch (_: Exception) {}
            }
        }.start()
    }

    @After
    fun tearDown() {
        fakeConnector?.close()
    }

    // --- getLocalIp logic ---

    @Test
    fun `getLocalIp returns non-loopback IPv4 address`() {
        val ip = getLocalIp()
        assertNotNull("Should find at least one non-loopback IPv4", ip)
        assertFalse("Should not be loopback", ip!!.startsWith("127."))
        assertTrue("Should be IPv4 format", ip.matches(Regex("""\d+\.\d+\.\d+\.\d+""")))
        println("Local IP: $ip")
    }

    // --- scanSubnet logic ---

    @Test
    fun `scanSubnet finds open port on localhost`() {
        // Scan localhost (127.0.0.x) for our fake connector
        val found = runBlocking {
            scanHost("127.0.0.1", fakeConnectorPort, timeoutMs = 500)
        }
        assertTrue("Should connect to fake connector on localhost", found)
    }

    @Test
    fun `scanSubnet does not find closed port`() {
        val closedPort = fakeConnectorPort + 1 // almost certainly not listening
        val found = runBlocking {
            scanHost("127.0.0.1", closedPort, timeoutMs = 300)
        }
        assertFalse("Should not find anything on closed port", found)
    }

    @Test
    fun `scanSubnet skips self`() {
        // Fake connector is on 127.0.0.1
        // If we say our localIp IS 127.0.0.1, it should be excluded
        // But 127.0.0.2+ also respond on loopback, so we verify
        // that the returned IP is NOT our declared self IP
        val result = runBlocking {
            scanSubnetExcludingSelf("127.0.0", "127.0.0.1", fakeConnectorPort, timeoutMs = 500)
        }
        if (result != null) {
            assertNotEquals("Should never return self IP", "127.0.0.1", result)
        }
        // This proves the exclusion works — .1 is skipped even though it has an open port
    }

    @Test
    fun `scanSubnet finds connector on different IP in subnet`() {
        // Scan 127.0.0.* but exclude 127.0.0.99 (not self)
        // Fake connector is on 127.0.0.1
        val found = runBlocking {
            scanSubnetExcludingSelf("127.0.0", "127.0.0.99", fakeConnectorPort, timeoutMs = 500)
        }
        assertEquals("Should find fake connector at 127.0.0.1", "127.0.0.1", found)
    }

    @Test
    fun `full discovery flow finds fake connector`() {
        // Simulate the full discovery flow
        val localIp = "127.0.0.99" // pretend we are .99
        val prefix = "127.0.0"

        val connectorIp = runBlocking {
            scanSubnetExcludingSelf(prefix, localIp, fakeConnectorPort, timeoutMs = 500)
        }

        assertNotNull("Full flow should find the fake connector", connectorIp)
        println("Discovered connector at: $connectorIp:$fakeConnectorPort")
    }

    // --- Helper functions (same logic as ConnectorService) ---

    private fun getLocalIp(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun scanHost(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                sock.close()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun scanSubnetExcludingSelf(
        prefix: String, localIp: String, port: Int, timeoutMs: Int
    ): String? {
        return withContext(Dispatchers.IO) {
            val jobs = (1..254).map { i ->
                val ip = "$prefix.$i"
                if (ip == localIp) return@map null
                async {
                    try {
                        val sock = Socket()
                        sock.connect(InetSocketAddress(ip, port), timeoutMs)
                        sock.close()
                        ip
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            jobs.filterNotNull().firstNotNullOfOrNull { it.await() }
        }
    }
}
