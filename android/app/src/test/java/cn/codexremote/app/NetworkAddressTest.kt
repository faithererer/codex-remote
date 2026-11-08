package cn.codexremote.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NetworkAddressTest {
    @Test fun pairingWorksWithoutATailscaleAddress() {
        val now = Instant.parse("2026-11-09T00:00:00Z")
        for (ip in listOf("10.0.0.8", "172.16.0.8", "172.31.255.254", "192.168.1.8")) {
            val code = JSONObject().put("v", 1).put("hostId", "11111111-1111-4111-8111-111111111111").put("url", "https://$ip:48761").put("fingerprint", "a".repeat(64)).put("secret", "b".repeat(64)).put("expiresAt", now.plusSeconds(300).toString())
            assertEquals("局域网", connectionKind(PairCode.parse(code.toString(), now.toEpochMilli()).host.url))
        }
    }
    @Test fun privateRangesExcludeAdjacentPublicAndSpecialAddresses() {
        for (ip in listOf("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "100.63.255.255", "100.128.0.0", "127.0.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "8.8.8.8")) {
            assertTrue(ip, runCatching { bridgeUrl("https://$ip:48761") }.isFailure)
        }
    }
    @Test fun tailscalePairingRemainsSupportedAtBothBoundaries() {
        assertEquals("Tailscale", connectionKind("https://100.64.0.1:48761"))
        assertEquals("Tailscale", connectionKind("https://100.127.255.254:48761"))
    }
    @Test fun invalidOrAmbiguousIpv4FormsCannotCarryCredentials() {
        for (ip in listOf("192.168.001.8", "010.0.0.8", "192.168.1.256", "192.168.1", "3232235784", "0xc0a80108", "[::ffff:192.168.1.8]", "[fd00::1]", "localhost", "computer.local", "example.com")) {
            assertTrue(ip, runCatching { bridgeUrl("https://$ip:48761") }.isFailure)
        }
    }
    @Test fun insecureOrCredentialBearingAddressesAreRejected() {
        for (url in listOf("http://192.168.1.8:48761", "https://user:pass@192.168.1.8:48761", "https://192.168.1.8:48761/api", "https://192.168.1.8:48761?next=other", "https://192.168.1.8:48761#other", "https://192.168.1.8:0", "https://192.168.1.8:65536")) {
            assertTrue(url, runCatching { connectionAddress(url) }.isFailure)
        }
    }
    @Test fun addressEntryUsesHttpsAndDefaultPortWithoutExposingAPath() {
        assertEquals("https://192.168.1.8:48761", connectionAddress(" 192.168.1.8 "))
        assertEquals("https://192.168.1.8:48762", connectionAddress("192.168.1.8:48762"))
        assertEquals("https://192.168.1.8:48761", connectionAddress("https://192.168.1.8/"))
    }
    private val host = Host("host-a", "My PC", "https://100.64.1.8:48761", "a".repeat(64), "b".repeat(64), "phone-a", false)
    private fun status(id: String = host.id) = JSONObject().put("hostId", id).put("instanceId", "instance-a").put("protocolVersion", 1)
    @Test fun switchingOnlyChangesAddressAndKeepsIdentityCredentialsAndAttention() {
        val switched = hostAtVerifiedAddress(host, "192.168.1.8", status())
        assertEquals(host.copy(url = "https://192.168.1.8:48761"), switched)
        assertEquals(host, hostAtVerifiedAddress(switched, host.url, status()))
    }
    @Test(expected = IllegalArgumentException::class) fun anotherComputerCannotReplacePairedHost() { hostAtVerifiedAddress(host, "192.168.1.9", status("host-b")) }
    @Test(expected = IllegalArgumentException::class) fun incompatibleServerCannotReplaceAddress() { hostAtVerifiedAddress(host, "192.168.1.9", status().put("protocolVersion", 2)) }
    @Test(expected = IllegalArgumentException::class) fun incompleteStatusCannotReplaceAddress() { hostAtVerifiedAddress(host, "192.168.1.9", status().also { it.remove("instanceId") }) }
    @Test fun lanFailureDoesNotRequireTheUserToEnableTailscale() {
        val message = connectionMessage(java.io.IOException(), "https://192.168.1.8:48761")
        assertTrue(message.contains("局域网")); assertFalse(message.contains("Tailscale"))
        assertTrue(connectionMessage(java.io.IOException(), host.url).contains("Tailscale"))
    }
    @Test fun certificateMismatchStaysAHardFailureOnLan() {
        val error = javax.net.ssl.SSLHandshakeException("closed").apply { initCause(java.security.cert.CertificateException("pin mismatch")) }
        val message = connectionMessage(error, "https://192.168.1.8:48761")
        assertTrue(message.contains("证书")); assertTrue(message.contains("拒绝"))
    }
}
