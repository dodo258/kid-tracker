package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.sense.Snapshot
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 屋里定不到卫星时用网络估算当主坐标（[NetFallback]，11-19 实测：P20 主坐标停在 12 小时前 2.8 公里外，三星全新装从没有过主坐标）。
 * 像引擎一样每 2 秒给 [Judge] 一份快照，它说取网络定位就按 [network] 给一个估算，记下报了什么。
 */
class NetFallbackTest {

    private class Phone(memory: WifiMemory = WifiMemory(), main: MainFix? = null) {
        val judge = Judge(memory, main = main)
        var time = T0

        /** 坐着、拿着手机（不到静止，也不算有动静）：一直是屋里走动 */
        var snapshot = Snapshot(
            time = 0, accMean = 0.4, accStd = 0.02, accN = 50, gyroMean = 0.03,
            net = Snapshot.Net.WIFI, wifiBssid = AP, wifiSsid = "HOME-5G", wifiRssi = -50, wifiMetered = false, wifiGateway = "192.168.1.1",
        )

        /** 取网络定位给的估算：往北多少米、误差、来源；null 是取不到 */
        var network: ((Long) -> Position?) = { null }
        val reports = mutableListOf<Position>()
        var fetches = 0

        fun run(seconds: Int) {
            repeat(seconds / 2) {
                time += 2_000
                take(judge.onSnapshot(time, snapshot.copy(time = time)))
            }
        }

        fun take(d: Decision?) {
            d ?: return
            d.report?.let { reports += judge.describe(it) }
            if (d.fetch) {
                fetches++
                take(judge.onFetched(time, network(time)))
            }
        }

        val estimates: List<Position> get() = reports.filter { it.provider == Provider.NETWORK }
    }

    private fun estimate(time: Long, north: Double, accuracy: Double = 86.0, provider: String = Provider.WIFI) =
        Position(time = time, latitude = lat(north), longitude = LON, accuracy = accuracy, provider = provider)

    /** 早上 2.8 公里外的卫星点（12 小时前） */
    private val morning = MainFix(lat(2_800.0), LON, 8.0, gps = true, at = T0 - 12 * 3_600_000L)

    @Test
    fun farMorningPointIsReplacedWithinTwoMinutesOfTheFirstEstimate() {
        val phone = Phone(main = morning)
        phone.network = { estimate(it, north = 20.0 + (it - T0) / 10_000.0) }
        phone.run(180)
        val first = phone.estimates.firstOrNull()
        assertNotNull(first, "两次估算一致（隔 ≥ 60 秒、在误差内）就当主坐标报：${phone.reports.map { it.provider }}")
        assertTrue(first.time - T0 <= 120_000, "2 分钟内换成估算：${(first.time - T0) / 1000} 秒")
        assertEquals(86.0, first.accuracy, "精度是估算误差")
        assertEquals(Provider.WIFI, first.netSource, "具体来源在 netSource")
        assertTrue(phone.reports.all { it.state == "zone" })
        assertEquals(1, phone.estimates.size, "同一个地方不反复当主坐标报")
        assertEquals(phone.judge.mainFix?.gps, false, "上一个主坐标记成估算")
        // 当前 Wi-Fi 没有锚：确认过的估算当临时锚点
        val memory = phone.judge.wifiMemory
        assertEquals(AP, memory.anchor)
        assertTrue(memory.anchorTemp)
        assertEquals(86.0, memory.anchorAcc)
    }

    @Test
    fun freshInstallGetsAMainCoordinateIndoors() {
        // 三星全新装：从没有过主坐标，屋里卫星点都不合格，以前只报时间，服务器记成 0,0
        val phone = Phone()
        phone.network = { estimate(it, north = 0.0, accuracy = 50.0) }
        phone.run(150)
        val first = assertNotNull(phone.estimates.firstOrNull(), "没有主坐标时两次估算一致就报")
        assertTrue(first.time - T0 <= 120_000)
        assertTrue(phone.reports.takeWhile { it !== first }.all { it.latitude == null })
    }

    @Test
    fun estimatesOfAMovingWifiNeverBecomeTheMainCoordinate() {
        // 跟着人走过的 Wi-Fi（车载、随身 Wi-Fi）：高德按它的 MAC 报的是它原来所在的地方
        val followed = Phone(WifiMemory(moving = listOf(AP)), main = morning)
        followed.network = { estimate(it, north = 0.0, accuracy = 40.0) }
        followed.run(900)
        assertTrue(followed.estimates.isEmpty())
        assertTrue(followed.reports.all { it.netLat == null }, "这种估算附加字段也不带")
        // 热点下的 Wi-Fi 定位同样不用
        val hotspot = Phone(main = morning)
        hotspot.snapshot = hotspot.snapshot.copy(wifiSsid = "小宝的iPhone", wifiGateway = "172.20.10.1")
        hotspot.network = { estimate(it, north = 0.0, accuracy = 40.0) }
        hotspot.run(900)
        assertTrue(hotspot.estimates.isEmpty())
        // 误差超过 200 米的不可信
        val cell = Phone(main = morning)
        cell.network = { estimate(it, north = 0.0, accuracy = 500.0, provider = Provider.CELL) }
        cell.run(900)
        assertTrue(cell.estimates.isEmpty())
        assertFalse(cell.judge.wifiMemory.anchorTemp, "不可信的估算也不当临时锚点")
    }

    @Test
    fun satellitePointWithinTenMinutesWins() {
        // 5 分钟前还有合格卫星点：先不换，满 10 分钟没卫星点才换
        val phone = Phone(main = morning.copy(at = T0 - 300_000))
        phone.network = { estimate(it, north = 0.0) }
        phone.run(240)
        assertTrue(phone.estimates.isEmpty(), "10 分钟内有过卫星点")
        phone.run(600)
        val first = assertNotNull(phone.estimates.firstOrNull())
        assertTrue(first.time >= T0 + 300_000, "卫星点满 10 分钟以后")
    }

    @Test
    fun estimateNearTheLastMainCoordinateKeepsIt() {
        // 离上一个主坐标不到 300 米 + 误差：不换（屋里估算抖几十米不挪圆点），但照样当临时锚点
        val phone = Phone(main = morning.copy(latitude = lat(250.0)))
        phone.network = { estimate(it, north = 0.0) }
        phone.run(900)
        assertTrue(phone.estimates.isEmpty())
        assertTrue(phone.judge.wifiMemory.anchorTemp)
    }

    @Test
    fun wifiWithASatelliteAnchorTrustsTheAnchor() {
        // 连着有卫星锚点的 Wi-Fi：锚点就是这里的卫星点，估算 600 米外多半是位置库记错了（P20 11-18 17:29）
        val anchored = WifiMemory(anchor = AP, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 8.0)
        val phone = Phone(anchored, main = morning.copy(latitude = lat(0.0)))
        phone.network = { estimate(it, north = 600.0, accuracy = 40.0) }
        phone.run(900)
        assertTrue(phone.estimates.isEmpty())
        assertFalse(phone.judge.wifiMemory.anchorTemp, "卫星锚点不换成估算")
    }

    @Test
    fun wifiWithASatelliteAnchorDoesNotFetchEveryMinute() {
        // 门第 1 轮审查：锚点 Wi-Fi 下两次估算确认了却不能当主坐标（CONFIRMED），新候选照样被当成「等确认」，每 60 秒催取一次。
        // 不能顶的估算不催：屋里照常 5 分钟取一次，15 分钟 4 次左右
        val anchored = WifiMemory(anchor = AP, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 8.0)
        val phone = Phone(anchored, main = morning.copy(latitude = lat(0.0)))
        phone.network = { estimate(it, north = 600.0, accuracy = 40.0) }
        phone.run(900)
        assertTrue(phone.estimates.isEmpty())
        assertTrue(phone.fetches <= 4, "不能当主坐标的估算不提前取：15 分钟取了 ${phone.fetches} 次")
    }

    /** 家里 Wi-Fi 被 20 米的卫星点锚过（锚点就在家） */
    private val home = WifiMemory(anchor = AP, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 20.0)

    @Test
    fun anchoredHomeWifiReportsTheAnchorWhenTheMainCoordinateIsFarAway() {
        // 11.0.15 核对：家里 Wi-Fi 有卫星锚点，回家一路没合格卫星点，主坐标停在 2.8 公里外；屋里只有网络估算（和锚点对得上）。
        // 以前连着锚 Wi-Fi 一律不顶，圆点一直停在远处
        val phone = Phone(home, main = morning)
        phone.network = { estimate(it, north = 25.0 + (it - T0) / 10_000.0) }
        phone.run(60)
        val first = assertNotNull(phone.reports.firstOrNull { it.provider == Provider.ANCHOR }, "${phone.reports.map { it.provider }}")
        val firstFetch = phone.reports.first { it.netLat != null }
        assertEquals(firstFetch.time, first.time, "一个估算和锚点对得上就顶，不等第二次")
        assertEquals(lat(0.0), first.latitude, "主坐标是锚点本身")
        assertEquals(20.0, first.accuracy, "精度用锚点的")
        assertEquals(Provider.WIFI, first.netSource, "估算照带")
        assertEquals(false, phone.judge.mainFix?.gps)
        phone.run(900)
        assertEquals(1, phone.reports.count { it.provider == Provider.ANCHOR }, "已经在家了：不反复顶")
        assertTrue(phone.estimates.isEmpty(), "顶的是锚点，不是估算")
        assertTrue(phone.fetches <= 5, "顶上以后照常 5 分钟取一次：15 分钟取了 ${phone.fetches} 次")
        assertFalse(phone.judge.wifiMemory.anchorTemp, "卫星锚点不换成估算")
    }

    @Test
    fun anchoredHomeWaitsForTenMinutesWithoutSatellitesThenHurries() {
        // 5 分钟前还有合格卫星点（远处）：先不顶；满 10 分钟那一刻马上取一次网络定位，对得上就顶锚点
        val phone = Phone(home, main = morning.copy(at = T0 - 300_000))
        phone.network = { estimate(it, north = 10.0) }
        phone.run(240)
        assertTrue(phone.reports.none { it.provider == Provider.ANCHOR }, "10 分钟内有过卫星点")
        phone.run(600)
        val first = assertNotNull(phone.reports.firstOrNull { it.provider == Provider.ANCHOR })
        assertTrue(first.time in T0 + 300_000..T0 + 310_000, "卫星点满 10 分钟就催取、顶上：${(first.time - T0) / 1000} 秒")
    }

    @Test
    fun anchoredHomeWithTheMainCoordinateAlreadyThereKeepsIt() {
        // 主坐标本来就在锚点附近：估算对得上也不换
        val phone = Phone(home, main = morning.copy(latitude = lat(40.0)))
        phone.network = { estimate(it, north = 30.0) }
        phone.run(900)
        assertTrue(phone.reports.none { it.provider == Provider.ANCHOR || it.provider == Provider.NETWORK })
    }

    @Test
    fun estimateFarFromTheSatelliteAnchorIsNeitherPromotedNorHurried() {
        val anchor = GpsPoint(T0 - 86_400_000, lat(0.0), LON, 20.0)
        val f = NetFallback(morning)
        // 离锚点 600 米、误差 40：600 − 20 − 40 > 100，对不上
        assertEquals(NetFallback.Verdict.NONE, f.estimate(T0, estimate(T0, 600.0, accuracy = 40.0), anchor))
        assertFalse(f.due(T0 + 600_000))
        assertNull(f.takePending())
        // 离锚点 150 米、误差 40：150 − 20 − 40 ≤ 100，对得上，马上顶锚点
        assertEquals(NetFallback.Verdict.PROMOTED, f.estimate(T0 + 1_000, estimate(T0 + 1_000, 150.0, accuracy = 40.0), anchor))
        val p = assertNotNull(f.takePending())
        assertEquals(Provider.ANCHOR, p.provider)
        assertEquals(lat(0.0), p.latitude)
        assertEquals(20.0, p.accuracy)
    }

    @Test
    fun firstOpenSatelliteFixReplacesTheTemporaryAnchor() {
        val phone = Phone(main = morning)
        phone.network = { estimate(it, north = 0.0) }
        phone.run(180)
        assertTrue(phone.judge.wifiMemory.anchorTemp)
        // 窗边定到一个开阔卫星点（离临时锚点 30 米，以前同一个锚 50 米内不挪）
        val t = phone.time + 2_000
        phone.time = t
        phone.take(phone.judge.onSnapshot(t, phone.snapshot.copy(time = t, fix = Snapshot.Fix(Provider.GPS, t - 500, lat(30.0), LON, 6.0))))
        val memory = phone.judge.wifiMemory
        assertEquals(AP, memory.anchor)
        assertFalse(memory.anchorTemp)
        assertEquals(6.0, memory.anchorAcc)
        assertEquals(lat(30.0), memory.anchorLat)
    }

    @Test
    fun temporaryAnchorFoundFarAwayIsNotRememberedAsAMovingWifi() {
        // 临时锚点本身可能差几百米：量出离开了只清锚，不把家里的 Wi-Fi 记进跟着人走过的名单（不然家里的 Wi-Fi 估算以后都不用）
        val book = WifiBook()
        val home = Snapshot(time = T0, net = Snapshot.Net.WIFI, wifiBssid = AP, wifiSsid = "HOME-5G", wifiMetered = false)
        assertTrue(book.tempAnchor(home, GpsPoint(T0, lat(0.0), LON, 150.0)))
        assertEquals(WifiKind.ANCHOR, book.kind(home))
        assertTrue(book.release(followed = true))
        assertTrue(book.memory.moving.isEmpty())
        assertFalse(book.memory.anchorTemp)
        // 跟着人走过的 Wi-Fi 不当临时锚点
        val followed = WifiBook(WifiMemory(moving = listOf(AP)))
        assertFalse(followed.tempAnchor(home, GpsPoint(T0, lat(0.0), LON, 150.0)))
    }

    @Test
    fun everyReportCarriesTheRecentTrustedEstimate() {
        // 主坐标就在这里（估算和它对得上，不当主坐标）：之后屋里取不到估算时，报的只带时间的点也带上 10 分钟内那个估算
        val phone = Phone(main = morning.copy(latitude = lat(0.0)))
        var calls = 0
        phone.network = { if (calls++ == 0) estimate(it, north = 10.0, accuracy = 40.0) else null }
        phone.run(20)
        val got = phone.reports.last()
        assertEquals(lat(10.0), got.netLat)
        phone.run(300)
        val next = phone.reports.last()
        assertTrue(next.time - got.time >= 290_000, "下一次屋里取点")
        assertNull(next.latitude)
        assertEquals(lat(10.0), next.netLat, "5 分钟前的估算照带")
        assertEquals(40.0, next.netAcc)
        assertEquals(Provider.WIFI, next.netSource)
        phone.run(700)
        assertNull(phone.reports.last().netLat, "超过 10 分钟的不带")
    }

    @Test
    fun restartRemembersTheMainCoordinate() {
        // 进程被杀重启：上一个主坐标存在状态库里接着比，家里的估算不再当主坐标报一遍
        val phone = Phone(main = morning)
        phone.network = { estimate(it, north = 0.0) }
        phone.run(180)
        assertEquals(1, phone.estimates.size)
        val again = Phone(phone.judge.wifiMemory, phone.judge.mainFix)
        again.time = phone.time
        again.network = phone.network
        again.run(900)
        assertTrue(again.estimates.isEmpty())
    }

    @Test
    fun satelliteReportCancelsAWaitingEstimateAndRestartsTheTenMinutes() {
        val f = NetFallback(morning)
        assertEquals(NetFallback.Verdict.NONE, f.estimate(T0, estimate(T0, 0.0), anchor = null))
        assertTrue(f.due(T0 + 60_000), "离主坐标远：60 秒后催一次")
        f.asked()
        assertFalse(f.due(T0 + 62_000), "同一个候选只催一次")
        assertEquals(NetFallback.Verdict.PROMOTED, f.estimate(T0 + 60_000, estimate(T0 + 60_000, 30.0), anchor = null))
        f.reported(T0 + 61_000, estimate(T0 + 61_000, 5.0, accuracy = 6.0).copy(provider = Provider.GPS))
        assertNull(f.takePending(), "报了卫星点：等着的估算作废")
        assertEquals(NetFallback.Verdict.NONE, f.estimate(T0 + 200_000, estimate(T0 + 200_000, 2_000.0), anchor = null))
        assertEquals(
            NetFallback.Verdict.NONE, f.estimate(T0 + 300_000, estimate(T0 + 300_000, 2_000.0), anchor = null),
            "卫星点刚报过，10 分钟内不换",
        )
    }

    @Test
    fun bouncingEstimatesStopHurryingAfterThreeTries() {
        val f = NetFallback(morning)
        var t = T0
        repeat(NetFallback.MAX_ASKS) { i ->
            f.estimate(t, estimate(t, i * 1_000.0), anchor = null)
            assertTrue(f.due(t + 60_000), "第 ${i + 1} 个对不上的候选还催")
            f.asked()
            t += 60_000
        }
        f.estimate(t, estimate(t, 9_000.0), anchor = null)
        assertFalse(f.due(t + 60_000), "接连 ${NetFallback.MAX_ASKS} 个都对不上：不再每分钟取")
    }

    private companion object {
        const val T0 = 1_800_000_000_000L
        const val AP = "3c:8c:aa:00:00:01"
        const val LAT = 30.27
        const val LON = 120.15
        fun lat(meters: Double) = LAT + meters / (6_371_000.0 * PI / 180)
    }
}
