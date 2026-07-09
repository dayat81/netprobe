package com.telcoagent.udpclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Test internet koneksi dengan dan tanpa VPN.
 *
 * Flow:
 * 1. Test baseline connectivity (tanpa VPN)
 * 2. Connect VPN via UI
 * 3. Test connectivity through VPN
 * 4. Disconnect VPN
 * 5. Verify kembali ke baseline
 */
@RunWith(AndroidJUnit4::class)
class VpnConnectivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    companion object {
        private const val TEST_URL = "https://netprobe.xyz/api/health"
        private const val DNS_TEST_HOST = "google.com"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
        private const val VPN_CONNECT_WAIT_MS = 8000L
        private const val VPN_DISCONNECT_WAIT_MS = 3000L
    }

    // ── Helper: Network checks ──────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun httpGet(url: String, timeoutMs: Int = CONNECT_TIMEOUT_MS): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        return try {
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText().take(500)
            Pair(code, body)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "unknown error")
        } finally {
            conn.disconnect()
        }
    }

    private fun dnsResolve(host: String): Boolean {
        return try {
            InetAddress.getByName(host)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getActiveNetworkType(): String {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    // ── Helper: UI navigation ───────────────────────────────────────

    private fun navigateToTraceTab() {
        onView(withText("Trace")).perform(click())
        Thread.sleep(500)
    }

    private fun waitForVpnState(connected: Boolean, timeoutMs: Long = VPN_CONNECT_WAIT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = AnalysisVpnService.isConnected()
            if (state == connected) return
            Thread.sleep(500)
        }
    }

    // ── TEST: Baseline connectivity (tanpa VPN) ─────────────────────

    @Test
    fun baseline_networkIsAvailable() {
        // Pastikan VPN tidak connect dulu
        if (AnalysisVpnService.isConnected()) {
            navigateToTraceTab()
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
        Assert.assertTrue("Network should be available", isNetworkAvailable())
    }

    @Test
    fun baseline_dnsResolves() {
        if (AnalysisVpnService.isConnected()) {
            navigateToTraceTab()
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
        Assert.assertTrue("DNS should resolve $DNS_TEST_HOST", dnsResolve(DNS_TEST_HOST))
    }

    @Test
    fun baseline_httpReachable() {
        if (AnalysisVpnService.isConnected()) {
            navigateToTraceTab()
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
        val (code, body) = httpGet(TEST_URL)
        Assert.assertTrue("HTTP should return 2xx, got $code. Body: $body", code in 200..299)
    }

    @Test
    fun baseline_networkTypeNotVpn() {
        if (AnalysisVpnService.isConnected()) {
            navigateToTraceTab()
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
        val type = getActiveNetworkType()
        Assert.assertNotEquals("Network should NOT be VPN before connect", "vpn", type)
    }

    // ── TEST: VPN connect flow ──────────────────────────────────────

    @Test
    fun vpn_connectButtonExists() {
        navigateToTraceTab()
        onView(withText("Connect VPN"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun vpn_canConnectAndDisconnect() {
        navigateToTraceTab()

        // Connect
        onView(withText("Connect VPN")).perform(click())
        waitForVpnState(true, VPN_CONNECT_WAIT_MS)

        // Verify connected state in UI
        onView(withText("Disconnect"))
            .check(matches(isDisplayed()))

        // Disconnect
        onView(withText("Disconnect")).perform(click())
        waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)

        // Verify disconnected state
        onView(withText("Connect VPN"))
            .check(matches(isDisplayed()))
    }

    // ── TEST: Connectivity through VPN ──────────────────────────────

    @Test
    fun vpn_networkAvailableThroughVpn() {
        navigateToTraceTab()

        // Connect VPN
        onView(withText("Connect VPN")).perform(click())
        waitForVpnState(true, VPN_CONNECT_WAIT_MS)

        try {
            Assert.assertTrue("Network should be available through VPN", isNetworkAvailable())
        } finally {
            // Cleanup: disconnect
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
    }

    @Test
    fun vpn_dnsResolvesThroughVpn() {
        navigateToTraceTab()

        onView(withText("Connect VPN")).perform(click())
        waitForVpnState(true, VPN_CONNECT_WAIT_MS)

        try {
            Assert.assertTrue("DNS should resolve through VPN", dnsResolve(DNS_TEST_HOST))
        } finally {
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
    }

    @Test
    fun vpn_httpReachableThroughVpn() {
        navigateToTraceTab()

        onView(withText("Connect VPN")).perform(click())
        waitForVpnState(true, VPN_CONNECT_WAIT_MS)

        try {
            val (code, body) = httpGet(TEST_URL)
            Assert.assertTrue("HTTP through VPN should return 2xx, got $code. Body: $body", code in 200..299)
        } finally {
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
    }

    @Test
    fun vpn_networkTypeChangesToVpn() {
        navigateToTraceTab()

        val typeBefore = getActiveNetworkType()

        onView(withText("Connect VPN")).perform(click())
        waitForVpnState(true, VPN_CONNECT_WAIT_MS)

        try {
            val typeAfter = getActiveNetworkType()
            Assert.assertEquals("Network type should be 'vpn' when connected", "vpn", typeAfter)
        } finally {
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
    }

    @Test
    fun vpn_serverReachableThroughTunnel() {
        navigateToTraceTab()

        onView(withText("Connect VPN")).perform(click())
        waitForVpnState(true, VPN_CONNECT_WAIT_MS)

        try {
            // Test server langsung via hostname VPN
            val (code, _) = httpGet("https://netprobe.xyz/api/health")
            Assert.assertTrue("Server should be reachable through tunnel, got HTTP $code", code in 200..499)
        } finally {
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)
        }
    }

    // ── TEST: Before vs After comparison ────────────────────────────

    @Test
    fun vpn_connectivityBeforeAndAfter() {
        navigateToTraceTab()

        // BEFORE VPN
        val dnsBefore = dnsResolve(DNS_TEST_HOST)
        val (codeBefore, _) = httpGet(TEST_URL)
        val typeBefore = getActiveNetworkType()

        // CONNECT VPN
        onView(withText("Connect VPN")).perform(click())
        waitForVpnState(true, VPN_CONNECT_WAIT_MS)

        try {
            // THROUGH VPN
            val dnsDuring = dnsResolve(DNS_TEST_HOST)
            val (codeDuring, _) = httpGet(TEST_URL)
            val typeDuring = getActiveNetworkType()

            // Assertions
            Assert.assertTrue("DNS should work before VPN", dnsBefore)
            Assert.assertTrue("DNS should work through VPN", dnsDuring)
            Assert.assertTrue("HTTP should work before VPN", codeBefore in 200..299)
            Assert.assertTrue("HTTP should work through VPN", codeDuring in 200..299)
            Assert.assertNotEquals("Type should change when VPN connects", typeBefore, typeDuring)
        } finally {
            // Cleanup
            onView(withText("Disconnect")).perform(click())
            waitForVpnState(false, VPN_DISCONNECT_WAIT_MS)

            // AFTER disconnect
            val dnsAfter = dnsResolve(DNS_TEST_HOST)
            val typeAfter = getActiveNetworkType()

            Assert.assertTrue("DNS should work after VPN disconnect", dnsAfter)
            Assert.assertNotEquals("Should not be VPN after disconnect", "vpn", typeAfter)
        }
    }
}
