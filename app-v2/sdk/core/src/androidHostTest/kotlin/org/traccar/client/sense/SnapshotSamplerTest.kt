package org.traccar.client.sense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotSamplerTest {

    @Test
    fun significantMotionStaysArmedWhileAwakeWithoutWakeUpAccelerometer() {
        // 审查退回：屋里走动（FIDGET）时醒着、卫星关着、没唤醒锁，手机又没有唤醒型加速度——CPU 一睡就没快照，出门走路只能靠显著运动叫醒
        assertTrue(wantSignificantMotion(sleeping = false, accelWakeUp = false, inGrace = false))
        // 刚被它叫醒的宽限期里不重挂，过了再挂
        assertFalse(wantSignificantMotion(sleeping = false, accelWakeUp = false, inGrace = true))
    }

    @Test
    fun wakeUpAccelerometerNeedsNoTriggerWhileAwake() {
        assertFalse(wantSignificantMotion(sleeping = false, accelWakeUp = true, inGrace = false))
    }

    @Test
    fun sleepingAlwaysArmsSignificantMotion() {
        assertTrue(wantSignificantMotion(sleeping = true, accelWakeUp = true, inGrace = false))
        assertTrue(wantSignificantMotion(sleeping = true, accelWakeUp = false, inGrace = false))
    }

    @Test
    fun systemStateLandsOnEverySnapshot() {
        // R6 录制时发现：屏幕、充电、飞行模式、联网、Wi-Fi、步数一直是空的，判断层看不到飞行模式和 Wi-Fi
        val window = Snapshot(time = 1_000L, accMean = 0.1, accN = 40)
        val fix = Snapshot.Fix(provider = "gps", time = 900L, latitude = 30.0, longitude = 120.0, accuracy = 5.0, speed = null, bearing = null)
        val state = SystemState(
            screen = false, charging = true, airplane = true, stepsTotal = 1234L, net = Snapshot.Net.WIFI,
            wifiBssid = "aa:bb", wifiSsid = "home", wifiRssi = -50, wifiMetered = false, wifiGateway = "192.168.1.1",
            netLink = 3, cell = Cell(tac = 22_801, id = 1234L, dbm = -97), fix = fix,
        )
        val s = state.applyTo(window)
        assertEquals(false, s.screen)
        assertEquals(true, s.charging)
        assertEquals(true, s.airplane)
        assertEquals(1234L, s.stepsTotal)
        assertEquals(Snapshot.Net.WIFI, s.net)
        assertEquals("aa:bb", s.wifiBssid)
        assertEquals("home", s.wifiSsid)
        assertEquals(-50, s.wifiRssi)
        assertEquals(false, s.wifiMetered)
        assertEquals("192.168.1.1", s.wifiGateway)
        assertEquals(fix, s.fix)
        assertEquals(22_801, s.cellTac)
        assertEquals(1234L, s.cellId)
        assertEquals(-97, s.cellDbm)
        assertEquals(3, s.netLink, "同一个 Wi-Fi 断开重连，判断层靠连接代次看出来（审计 11.0.11 第 3 条）")
        assertEquals(0.1, s.accMean)
        assertEquals(40, s.accN)
    }
    @Test
    fun linkChangeIsWhatWakesAnAsleepSnapshot() {
        // 11.0.16 实测：重启后接着静止、采样器一份快照都没出，上报一直 net=none。睡着时联网方式、Wi-Fi、连接代次变了（或还没出过快照）才出一份
        val state = SystemState(
            screen = false, charging = false, airplane = false, stepsTotal = null, net = Snapshot.Net.WIFI,
            wifiBssid = "aa:bb", wifiSsid = "home", wifiRssi = -50, wifiMetered = false, wifiGateway = "192.168.1.1", netLink = 1, fix = null,
        )
        assertTrue(state.linkChanged(null), "还没出过快照")
        val last = state.applyTo(Snapshot(time = 1_000L))
        assertFalse(state.linkChanged(last))
        assertFalse(state.copy(wifiRssi = -70, wifiGateway = "10.0.0.1", cell = Cell(tac = 1, id = 2L, dbm = -90)).linkChanged(last), "信号强度、网关、基站变了不算")
        assertTrue(state.copy(net = Snapshot.Net.NONE, wifiBssid = "", wifiSsid = "").linkChanged(last), "断网")
        assertTrue(state.copy(wifiBssid = "cc:dd").linkChanged(last), "换了 Wi-Fi")
        assertTrue(state.copy(netLink = 2).linkChanged(last), "同一个 Wi-Fi 断开重连")
        assertTrue(state.copy(wifiMetered = true).linkChanged(last))
    }
}
