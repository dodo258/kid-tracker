package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.sense.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 方案 3a 定稿的实例：P20 11-18 17:29 到了新地方、连上那里的 Wi-Fi，高德 Wi-Fi 定位报回 1.1 公里外的工作室。 */
class JudgeTest {

    private val fidget = Snapshot(
        time = 0, accMean = 0.4, accStd = 0.3, accN = 50, gyroMean = 0.08,
        net = Snapshot.Net.WIFI, wifiBssid = "new-place", wifiSsid = "Cafe", wifiMetered = false,
    )

    @Test
    fun wifiLocationFarAwayChangesNeitherCoordinateNorState() {
        val judge = Judge()
        val reports = mutableListOf<Position>()
        var fetch = false
        var t = 1_000_000L
        repeat(10) {
            t += 2_000
            val d = judge.onSnapshot(t, fidget.copy(time = t))
            d.report?.let { reports += judge.describe(it) }
            fetch = fetch || d.fetch
        }
        assertTrue(fetch, "屋里：取一次网络定位")
        // 取回来的是高德按这里的 Wi-Fi 报的工作室位置（1.1 公里外）
        val studio = Position(latitude = 30.284, longitude = 120.150, accuracy = 89.0, time = t, provider = Provider.WIFI)
        val d = judge.onFetched(t + 1_000, studio)
        val p = judge.describe(assertNotNull(d.report))
        assertNull(p.latitude, "不报工作室坐标")
        assertEquals(30.284, p.netLat)
        assertEquals("zone", p.state, "状态不因为 Wi-Fi 定位改成 moving")
        assertEquals(false, p.wifiSame, "换了 Wi-Fi：只说明可能动了")
        assertTrue(reports.all { it.latitude == null && it.state == "zone" })
    }

    @Test
    fun noSensorDataAtAllStillReportsFromTheFirstBreath() {
        // 手机没有加速度计（或传感器服务坏了）：一份快照都没有，判断层不知道运动状态。撤掉兜底就一个点都不报
        val judge = Judge()
        val d = judge.onTick(1_000_000L)
        val p = judge.describe(assertNotNull(d.report, "第一次呼吸就报"))
        assertEquals("zone", p.state)
        assertTrue(d.fetch, "按屋里走动取网络定位")
        judge.onFetched(1_001_000L, null)
        val later = judge.onTick(1_000_000L + 300_000)
        assertTrue(later.fetch, "之后 5 分钟取一次")
    }

    @Test
    fun restoredStillBreathesAtOnceAndStaysStill() {
        // 进程被杀、重启后接着静止：第一次呼吸就报，第一份安静的快照不改判「在附近活动」（不再先报一条 zone）
        val judge = Judge(restored = org.traccar.client.motion.Motion.STILL)
        // 没有主坐标：这次呼吸顺带取网络定位，取回来（这里没取到）再报
        val tick = judge.onTick(2_000_000L)
        assertTrue(tick.fetch)
        assertNull(tick.report)
        val breath = judge.describe(assertNotNull(judge.onFetched(2_000_001L, null).report))
        assertEquals("still", breath.state)
        val quiet = Snapshot(time = 2_000_002L, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001)
        val d = judge.onSnapshot(2_000_002L, quiet)
        assertNull(d.report)
        assertEquals(org.traccar.client.motion.Motion.STILL, judge.motion)
    }
}
