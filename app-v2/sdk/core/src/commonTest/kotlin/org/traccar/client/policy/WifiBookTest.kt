package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.motion.Motion
import org.traccar.client.sense.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 方案 3a 定稿：Wi-Fi 只辅助状态（还连着上次卫星定位时的 Wi-Fi 就没离开），热点按计费、网段、名字、跟着人走认；主坐标只放卫星点。 */
class WifiBookTest {

    private val homeLat = 30.2741
    private val homeLon = 120.1551

    private fun wifi(time: Long, bssid: String = "a8:aa", ssid: String = "HOME-5G", rssi: Int = -55, metered: Boolean = false, gateway: String = "192.168.1.1") =
        Snapshot(time = time, net = Snapshot.Net.WIFI, wifiBssid = bssid, wifiSsid = ssid, wifiRssi = rssi, wifiMetered = metered, wifiGateway = gateway)

    private fun gps(time: Long, northMeters: Double = 0.0, accuracy: Double = 8.0) =
        GpsPoint(time, homeLat + northMeters / 111_320.0, homeLon, accuracy)

    private val cell = Snapshot(time = 0, net = Snapshot.Net.CELL)

    @Test
    fun wifiAtTheLastSatelliteFixIsTheAnchorWithItsPlace() {
        val book = WifiBook()
        assertEquals(WifiKind.OTHER, book.kind(wifi(0)), "没卫星确认过：只说明连着 Wi-Fi")
        assertTrue(book.observe(wifi(1_000), Motion.WALK, gps(1_000)))
        assertEquals(WifiKind.ANCHOR, book.kind(wifi(2_000)), "卫星定位时连着它：还连着就是没离开")
        assertEquals("a8:aa", book.anchorBssid(wifi(2_000)))
        assertEquals(WifiKind.OTHER, book.kind(wifi(3_000, bssid = "bb:bb")), "换了 Wi-Fi：可能动了")
        assertEquals(WifiMemory(anchor = "a8:aa", anchorLat = homeLat, anchorLon = homeLon, anchorAcc = 8.0), book.memory)
        val restored = WifiBook(book.memory)
        assertEquals(WifiKind.ANCHOR, restored.kind(wifi(4_000)), "重启接着用")
        assertEquals(GpsPoint(0, homeLat, homeLon, 8.0), restored.anchorPoint, "锚点位置也接着用")
        // 同一个锚上 50 米内的卫星点不挪锚点（一直开着卫星时不用每秒存）
        assertFalse(book.observe(wifi(5_000), Motion.FIDGET, gps(5_000, northMeters = 30.0)))
        assertTrue(book.observe(wifi(6_000), Motion.FIDGET, gps(6_000, northMeters = 80.0)))
        assertEquals(homeLat + 80.0 / 111_320.0, book.memory.anchorLat)
    }

    @Test
    fun oldMemoryWithoutPlaceIsNotAnAnchorUntilTheNextFix() {
        // 老版本存的记忆只有 BSSID：量不了离锚点多远，先不当锚（不把人关在屋里），下一个好的卫星点补上位置
        val book = WifiBook(WifiMemory(anchor = "a8:aa"))
        assertEquals(WifiKind.OTHER, book.kind(wifi(0)))
        book.observe(wifi(1_000), Motion.FIDGET, gps(1_000))
        assertEquals(WifiKind.ANCHOR, book.kind(wifi(2_000)))
    }

    @Test
    fun badSatelliteFixDoesNotAnchor() {
        val book = WifiBook()
        assertFalse(book.observe(wifi(0), Motion.WALK, gps(0, accuracy = 120.0)))
        assertEquals(WifiKind.OTHER, book.kind(wifi(1_000)))
    }

    @Test
    fun newPlaceNewWifiWithoutSatelliteIsNotAnchor() {
        // 在家卫星定位过，走到新地方连上新 Wi-Fi，屋里没卫星：新 Wi-Fi 不算没离开
        val book = WifiBook()
        book.observe(wifi(0), Motion.FIDGET, gps(0))
        book.observe(cell.copy(time = 60_000), Motion.WALK, gps(60_000, northMeters = 800.0))
        assertNull(book.memory.anchor, "路上卫星定位时没连 Wi-Fi：锚清掉")
        assertEquals(WifiKind.OTHER, book.kind(wifi(120_000, bssid = "school")))
        assertEquals(WifiKind.OTHER, book.kind(wifi(120_000)), "家里的 Wi-Fi 也不再是锚")
    }

    @Test
    fun wifiFollowingThePersonIsOnlyAHint() {
        val book = WifiBook()
        // 连着一个名字、计费都不像热点的 Wi-Fi（随身 Wi-Fi），卫星定位时连着它（成了锚）
        book.observe(wifi(0, "c8:00"), Motion.WALK, gps(0))
        assertEquals(WifiKind.ANCHOR, book.kind(wifi(1_000, "c8:00")))
        assertFalse(book.hinted("c8:00"))
        // 坐车、走路时还没判成在路上（探测量的、别的 App 定的点）：没超出覆盖范围，锚点不跟着挪
        assertFalse(book.observe(wifi(20_000, "c8:00"), Motion.WALK, gps(20_000, northMeters = 150.0)))
        assertEquals(homeLat, book.anchorPoint?.latitude)
        // 走了 300 米还连着：Wi-Fi 跟着人走，锚清掉，记成提示
        assertTrue(book.observe(wifi(40_000, "c8:00"), Motion.WALK, gps(40_000, northMeters = 300.0)))
        assertNull(book.memory.anchor)
        assertEquals(listOf("c8:00"), book.memory.moving)
        assertTrue(book.hinted("c8:00"))
        assertEquals(WifiKind.OTHER, book.kind(wifi(41_000, "c8:00")), "只是提示，不按热点算（不是永久标签）")
        // 带回家、在它下面定到好的卫星点：照样当锚，锚在家里
        assertTrue(book.observe(wifi(600_000, "c8:00"), Motion.FIDGET, gps(600_000, northMeters = 2_000.0)))
        assertEquals(WifiKind.ANCHOR, book.kind(wifi(601_000, "c8:00")))
        assertEquals(homeLat + 2_000.0 / 111_320.0, book.anchorPoint?.latitude)
    }

    @Test
    fun indoorDriftDoesNotReleaseTheAnchor() {
        val book = WifiBook()
        book.observe(wifi(0), Motion.WALK, gps(0))
        // 屋里走动时卫星漂了 250 米：没在走路坐车，不算跟着人走
        assertTrue(book.observe(wifi(30_000), Motion.FIDGET, gps(30_000, northMeters = 250.0)))
        assertTrue(book.memory.moving.isEmpty())
        assertEquals(WifiKind.ANCHOR, book.kind(wifi(40_000)))
        // 覆盖范围 200 米加上两个点的精度：精度 40 米时 260 米内都算还在
        val far = WifiBook()
        far.observe(wifi(0), Motion.WALK, gps(0, accuracy = 30.0))
        assertFalse(far.beyond(gps(1_000, northMeters = 250.0, accuracy = 40.0)))
        assertTrue(far.beyond(gps(1_000, northMeters = 280.0, accuracy = 40.0)))
    }

    @Test
    fun randomMacIsAHint() {
        val book = WifiBook()
        assertTrue(book.hinted("ae:12:34:56:78:9a"), "第一个字节第二低位是 1：本地管理（随机）MAC，新系统的手机热点这样")
        assertFalse(book.hinted("3c:8c:93:10:00:30"))
        assertFalse(book.hinted(""))
    }

    @Test
    fun anchorRemembersCellsSeenThere() {
        val book = WifiBook()
        val home = wifi(0).copy(cellTac = 100, cellId = 1)
        book.observe(home, Motion.FIDGET, gps(0))
        assertEquals(listOf("100-1"), book.memory.anchorCells)
        assertFalse(book.unfamiliarCell(home.copy(time = 1_000)))
        assertTrue(book.unfamiliarCell(home.copy(time = 2_000, cellId = 2)), "没见过的基站")
        assertTrue(book.unfamiliarCell(home.copy(time = 2_000, cellTac = 101)), "跟踪区变了")
        assertTrue(book.learnCell(home.copy(time = 3_000, cellId = 2)))
        assertFalse(book.unfamiliarCell(home.copy(time = 4_000, cellId = 2)), "量过、还在这里的基站记下来，家里来回切不算")
        assertFalse(book.unfamiliarCell(home.copy(time = 5_000, cellId = null)), "没卡读不到基站：不算")
        assertEquals(listOf("100-1", "100-2"), WifiBook(book.memory).memory.anchorCells, "重启接着用")
    }

    @Test
    fun hotspotsAreRecognisedByRules() {
        val book = WifiBook()
        assertEquals(WifiKind.HOTSPOT, book.kind(wifi(0, metered = true)), "安卓标了计费")
        assertEquals(WifiKind.HOTSPOT, book.kind(wifi(0, gateway = "172.20.10.1")), "iPhone 热点网段，安卓没标计费")
        assertEquals(WifiKind.HOTSPOT, book.kind(wifi(0, ssid = "小宝的iPhone")))
        assertEquals(WifiKind.HOTSPOT, book.kind(wifi(0, ssid = "爸爸的手机")))
        assertEquals(WifiKind.HOTSPOT, book.kind(wifi(0, ssid = "AndroidAP_1234")))
        assertEquals(WifiKind.OTHER, book.kind(wifi(0, ssid = "HUAWEI-1A2B")), "路由器默认名不算")
        assertEquals(WifiKind.NONE, book.kind(cell))
        // 热点不当锚
        book.observe(wifi(0, "cc:cc", metered = true), Motion.FIDGET, gps(0))
        assertNull(book.memory.anchor)
    }

    @Test
    fun unreadableBssidIsNeverAnchor() {
        val book = WifiBook()
        book.observe(wifi(0, bssid = ""), Motion.WALK, gps(0))
        assertEquals(WifiKind.OTHER, book.kind(wifi(1_000, bssid = "")))
    }

    @Test
    fun homeWifiLocationDatabaseWrongNeverMovesTheMainCoordinate() {
        // 家里 Wi-Fi 的位置库记错（高德按它报到 1 公里外）：网络定位不放主坐标，只放附加字段
        val wrong = Position(latitude = homeLat + 0.009, longitude = homeLon, accuracy = 60.0, time = 5L, provider = Provider.WIFI)
        val p = wrong.satelliteOnly()
        assertNull(p.latitude)
        assertNull(p.longitude)
        assertEquals(homeLat + 0.009, p.netLat)
        assertEquals(60.0, p.netAcc)
        assertEquals(Provider.WIFI, p.netSource)
        val sat = wrong.copy(provider = Provider.GPS)
        assertEquals(sat, sat.satelliteOnly(), "卫星点原样放主坐标")
    }

    @Test
    fun wifiLocationIsNotUsedOnAHotspot() {
        assertTrue(usableFix("wifi", WifiKind.NONE), "没连 Wi-Fi：按周围扫到的 Wi-Fi 定位，放附加字段")
        assertTrue(usableFix("wifi", WifiKind.ANCHOR))
        assertTrue(usableFix("wifi", WifiKind.OTHER))
        assertFalse(usableFix("wifi", WifiKind.HOTSPOT), "连着热点：高德会按热点的 MAC 报回家")
        assertTrue(usableFix("cell", WifiKind.HOTSPOT), "基站照用")
    }

    @Test
    fun netLabels() {
        assertEquals("wifi", netLabel(Snapshot.Net.WIFI, WifiKind.ANCHOR))
        assertEquals("wifi", netLabel(Snapshot.Net.WIFI, WifiKind.OTHER))
        assertEquals("hotspot", netLabel(Snapshot.Net.WIFI, WifiKind.HOTSPOT))
        assertEquals("cellular", netLabel(Snapshot.Net.CELL, WifiKind.NONE))
        assertEquals("none", netLabel(Snapshot.Net.NONE, WifiKind.NONE))
        assertEquals("none", netLabel(null, WifiKind.NONE))
    }
}
