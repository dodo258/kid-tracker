package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.motion.Motion
import org.traccar.client.sense.Snapshot
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 11.0.16 装机实测：两台手机装完在屋里一直静止，上报全是只带时间的呼吸、没有估算，主坐标一直没换（P20 停在早上的点，三星 0,0）。
 * 静止时传感器睡着、没有快照，判断层只靠呼吸闹钟（[Judge.onTick]）醒；这里就像那样只按呼吸调它（重启后接着静止），
 * 它说取网络定位就按 [network] 给一个估算。
 */
class StillLocateTest {

    private class Phone(main: MainFix? = null) {
        val judge = Judge(restored = Motion.STILL, main = main)
        var time = T0

        var network: ((Long) -> Position?) = { estimate(it, north = 0.0) }
        val reports = mutableListOf<Position>()
        val fetchedAt = mutableListOf<Long>()

        /** 每 [every] 毫秒响一次闹钟（真机是呼吸间隔加 2 秒），共 [millis] */
        fun breathe(millis: Long, every: Long = ReportPolicy.BREATH_EVERY + 2_000) {
            val end = time + millis
            while (time < end) {
                take(judge.onTick(time))
                time += every
            }
        }

        fun take(d: Decision?) {
            d ?: return
            d.report?.let { reports += judge.describe(it) }
            if (d.fetch) {
                fetchedAt += time
                take(judge.onFetched(time, network(time)))
            }
        }

        val located: Position? get() = reports.firstOrNull { it.provider == Provider.NETWORK }

        /** 任意一小时里取网络定位最多几次 */
        fun busiestHour(): Int = fetchedAt.maxOfOrNull { from -> fetchedAt.count { it >= from && it - from < 3_600_000L } } ?: 0
    }

    @Test
    fun stillFromRestartWithoutAMainCoordinateIsLocatedWithinTenMinutes() {
        // 三星：全新装从没有过主坐标，装完更新重启后接着静止
        val phone = Phone()
        phone.breathe(600_000)
        val first = assertNotNull(phone.located, "静止时呼吸顺带取网络定位，两次一致就当主坐标：${phone.reports.map { it.provider }}")
        assertTrue(first.time - T0 <= 600_000, "10 分钟内顶上：${(first.time - T0) / 1000} 秒")
        assertTrue(phone.reports.all { it.state == "still" })
    }

    @Test
    fun stillWithAStaleFarMainCoordinateIsLocatedWithinTenMinutes() {
        // P20：主坐标是早上 2.8 公里外的卫星点
        val phone = Phone(main = MainFix(lat(2_800.0), LON, 8.0, gps = true, at = T0 - 12 * 3_600_000L))
        phone.breathe(600_000)
        val first = assertNotNull(phone.located)
        assertTrue(first.time - T0 <= 600_000, "10 分钟内顶上：${(first.time - T0) / 1000} 秒")
        assertEquals(phone.judge.mainFix?.gps, false)
    }

    @Test
    fun locatedStillFetchesAtMostFourTimesAnHour() {
        // 顶上以后位置稳定：15 分钟最多取一次；闹钟就算一分钟响一次，任意一小时也不超过 4 次
        val phone = Phone()
        phone.breathe(4 * 3_600_000L, every = 60_000)
        assertNotNull(phone.located)
        assertTrue(phone.busiestHour() <= MAX_PER_HOUR, "一小时取了 ${phone.busiestHour()} 次：${phone.fetchedAt.map { (it - T0) / 60_000 }}")
        assertTrue(phone.fetchedAt.size >= 8, "4 小时里照样隔一阵取一次确认：${phone.fetchedAt.size}")
    }

    @Test
    fun failingFetchesAreCappedToo() {
        // 取不到（没网、高德出错）时也不是每次呼吸都取
        val phone = Phone()
        phone.network = { null }
        phone.breathe(3 * 3_600_000L)
        assertTrue(phone.busiestHour() <= MAX_PER_HOUR, "一小时取了 ${phone.busiestHour()} 次")
        assertTrue(phone.reports.count { it.state == "still" } >= 3 * 11, "呼吸照常：${phone.reports.size}")
    }

    @Test
    fun recentSatellitePointMeansNoFetch() {
        // 不到 10 分钟前刚报过合格卫星点：静止时不取
        val phone = Phone(main = MainFix(LAT, LON, 8.0, gps = true, at = T0 - 60_000))
        phone.breathe(500_000)
        assertTrue(phone.fetchedAt.isEmpty(), "有合格卫星点就不取：${phone.fetchedAt.map { (it - T0) / 1000 }}")
    }

    @Test
    fun systemOnlySnapshotWhileAsleepKeepsStillAndCarriesTheNetwork() {
        // 采样器睡着时联网变了出的快照（没有加速度）：还是静止，之后的上报带上连着的 Wi-Fi，不再 net=none
        val phone = Phone()
        val s = Snapshot(time = T0, elapsed = 1_000, net = Snapshot.Net.WIFI, wifiBssid = AP, wifiSsid = "HOME-5G", wifiMetered = false)
        phone.take(phone.judge.onSnapshot(T0, s))
        assertEquals(Motion.STILL, phone.judge.motion)
        assertTrue(phone.reports.isNotEmpty(), "重启后第一份快照就呼吸")
        assertTrue(phone.reports.all { it.net == "wifi" && it.state == "still" }, phone.reports.map { "${it.state} ${it.net}" }.toString())
    }

    private companion object {
        const val T0 = 1_800_000_000_000L
        const val MAX_PER_HOUR = 4 // 方案：静止时每小时最多 4 次网络定位
        const val AP = "3c:8c:aa:00:00:01"
        const val LAT = 30.27
        const val LON = 120.15
        fun lat(meters: Double) = LAT + meters / (6_371_000.0 * PI / 180)
        fun estimate(time: Long, north: Double, accuracy: Double = 86.0) =
            Position(time = time, latitude = lat(north), longitude = LON, accuracy = accuracy, provider = Provider.WIFI)
    }
}
