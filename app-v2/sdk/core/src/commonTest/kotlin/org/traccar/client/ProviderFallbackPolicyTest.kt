package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.traccar.client.ProviderFallbackPolicy.Companion.SUCCESS
import org.traccar.client.ProviderFallbackPolicy.Reason

class ProviderFallbackPolicyTest {

    private val auth = 7
    private val network = 4
    private val noSatellites = 14

    private fun policy() = ProviderFallbackPolicy(
        fatalErrors = setOf(auth, 9, 10),
        maxConsecutiveErrors = 5,
        offlineErrors = setOf(network),
        environmentErrors = setOf(noWifiNoCell, airplane, noSimWifiOff),
    )

    private val noWifiNoCell = 13
    private val airplane = 18
    private val noSimWifiOff = 19

    // P20 没插 SIM：关了 Wi-Fi 后高德 1 秒一次报 19，5 秒就整场切到系统定位，开了 Wi-Fi 也回不来
    @Test
    fun environmentErrorsNeverFallBack() {
        val policy = policy()
        repeat(50) {
            assertNull(policy.onResult(noSimWifiOff))
            assertNull(policy.onResult(noWifiNoCell))
            assertNull(policy.onResult(airplane, online = false))
        }
        assertFalse(policy.fallenBack)
        // 环境错误既不计数也不清零：真故障照样 5 次切走
        repeat(4) { assertNull(policy.onResult(noSatellites)) }
        assertNull(policy.onResult(noSimWifiOff))
        assertEquals(5, policy.onResult(noSatellites)?.consecutiveErrors)
    }

    // P20 实测：开机没网时高德连报 5 次网络异常就切走了，联网后也不回来
    @Test
    fun networkErrorsWhileOfflineDoNotFallBack() {
        val policy = policy()
        repeat(20) { assertNull(policy.onResult(network, online = false)) }
        assertFalse(policy.fallenBack)
        // Offline errors neither count nor reset: online errors still need five in a row
        repeat(4) { assertNull(policy.onResult(network)) }
        assertNull(policy.onResult(network, online = false))
        assertEquals(5, policy.onResult(network)?.consecutiveErrors)
    }

    @Test
    fun otherErrorsWhileOfflineStillCount() {
        val policy = policy()
        repeat(4) { assertNull(policy.onResult(noSatellites, online = false)) }
        assertEquals(noSatellites, policy.onResult(noSatellites, online = false)?.errorCode)
        assertEquals(true, policy().onResult(auth, online = false)?.fatal)
    }

    @Test
    fun authErrorFallsBackImmediately() {
        val policy = policy()
        assertEquals(Reason(auth, fatal = true, consecutiveErrors = 1), policy.onResult(auth))
        assertTrue(policy.fallenBack)
    }

    @Test
    fun authErrorAfterFixesStillFallsBackImmediately() {
        val policy = policy()
        repeat(3) { assertNull(policy.onResult(SUCCESS)) }
        assertEquals(true, policy.onResult(auth)?.fatal)
    }

    @Test
    fun sporadicErrorsDoNotFallBack() {
        val policy = policy()
        repeat(20) {
            repeat(4) { assertNull(policy.onResult(if (it % 2 == 0) network else noSatellites)) }
            assertNull(policy.onResult(SUCCESS))
        }
        assertFalse(policy.fallenBack)
    }

    @Test
    fun consecutiveErrorsFallBack() {
        val policy = policy()
        repeat(4) { assertNull(policy.onResult(network)) }
        assertEquals(Reason(noSatellites, fatal = false, consecutiveErrors = 5), policy.onResult(noSatellites))
        assertTrue(policy.fallenBack)
    }

    @Test
    fun switchIsReportedOnceAndLastsUntilSessionEnd() {
        val policy = policy()
        policy.onResult(auth)
        assertNull(policy.onResult(auth))
        assertNull(policy.onResult(SUCCESS))
        assertTrue(policy.fallenBack)
    }

    @Test
    fun nextSessionTriesPrimaryAgain() {
        val policy = policy()
        repeat(4) { policy.onResult(network) }
        policy.onSessionEnd()
        assertFalse(policy.fallenBack)
        // The count starts over, so four more errors are not enough
        repeat(4) { assertNull(policy.onResult(network)) }
        policy.onResult(auth)
        policy.onSessionEnd()
        assertFalse(policy.fallenBack)
        assertNull(policy.onResult(SUCCESS))
    }
}
