package cn.codexremote.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SafetyTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
    private fun code(url: String = "https://100.64.1.10:48761"): JSONObject = JSONObject().put("v", 1).put("hostId", "11111111-1111-4111-8111-111111111111").put("name", "PC").put("url", url).put("fingerprint", "a".repeat(64)).put("secret", "b".repeat(64)).put("expiresAt", Instant.ofEpochMilli(now + 300000).toString())
    private fun rejected(json: JSONObject) { try { PairCode.parse(json.toString(), now); fail("Unsafe pairing accepted") } catch (_: IllegalArgumentException) { } }
    @Test fun validPrivatePairing() { assertEquals("PC", PairCode.parse(code().toString(), now).host.name) }
    @Test fun rejectsPublicAddress() { rejected(code("https://8.8.8.8:48761")) }
    @Test fun rejectsPlainHttp() { rejected(code("http://100.64.1.10:48761")) }
    @Test fun rejectsDnsRebindingSource() { rejected(code("https://example.com:48761")) }
    @Test fun rejectsCredentialUrl() { rejected(code("https://user:pass@100.64.1.10:48761")) }
    @Test fun rejectsPathAndQuery() { rejected(code("https://100.64.1.10:48761/api")); rejected(code("https://100.64.1.10:48761?pin=other")) }
    @Test fun rejectsExpiredQr() { rejected(code().put("expiresAt", Instant.ofEpochMilli(now - 1).toString())) }
    @Test fun rejectsUnboundedExpiry() { rejected(code().put("expiresAt", Instant.ofEpochMilli(now + 86400000).toString())) }
    @Test fun rejectsInvalidPin() { rejected(code().put("fingerprint", "00")) }
    @Test fun rejectsUnknownVersion() { rejected(code().put("v", 2)) }
    @Test fun sameThreadDifferentPcHasDifferentIdentity() { val a = ThreadKey("pc-a", "same-thread"); val b = ThreadKey("pc-b", "same-thread"); assertNotEquals(a, b); val states = mapOf(a to "a", b to "b"); assertEquals(2, states.size); assertEquals("b", states[b]) }
    @Test fun hostRenameDoesNotChangeRouting() { val host = Host("pc-a", "name", "https://100.64.0.1:48761", "a".repeat(64), "b".repeat(64), "device"); assertEquals(host.id, host.copy(name = "new name").id) }
    @Test fun noFalseCompletionOnInitialSync() { assertFalse(shouldNotify(null, "completed", "", "")); assertTrue(shouldNotify("active", "completed", "", "")); assertFalse(shouldNotify("completed", "completed", "", "")) }
    @Test fun newApprovalNotifiesWithoutStatusChange() { assertTrue(shouldNotify("needsInput", "needsInput", "r1", "r2")); assertFalse(shouldNotify("needsInput", "needsInput", "r1", "r1")) }
    @Test fun networkFailureExplainsTailscale() { assertTrue(connectionMessage(java.io.IOException("connection closed")).contains("Tailscale")) }
    @Test fun badCertificateIsNeverReportedAsSafeNetworkRetry() { val error = javax.net.ssl.SSLHandshakeException("closed").apply { initCause(java.security.cert.CertificateException("pin mismatch")) }; assertTrue(connectionMessage(error).contains("证书")); assertTrue(connectionMessage(error).contains("拒绝")) }
}
