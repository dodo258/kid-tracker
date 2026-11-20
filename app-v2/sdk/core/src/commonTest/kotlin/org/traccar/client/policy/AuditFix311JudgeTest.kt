package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.motion.Motion
import org.traccar.client.motion.MotionClassifier
import org.traccar.client.sense.Snapshot
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 装机版 11.0.11 审计（docs/audits/2026-11-19-codex-install-311.md）判断层几条：1 手动发送的卫星点准入、2 被动定位先进快照、
 * 3 同一 Wi-Fi 断开重连、6 改快系统时间、7 静止时的高速卫星点、9 手动发送的 Wi-Fi 来源过滤。
 */
class AuditFix311JudgeTest {

    private val sit = Snapshot(time = 0, accMean = 0.4, accStd = 0.02, accN = 50, gyroMean = 0.03)
    private val ride = Snapshot(time = 0, accMean = -0.2, accStd = 1.2, accN = 50, gyroMean = 0.3)
    private val still = Snapshot(time = 0, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001)

    /** 车上平稳放着：不算安静，也不算有动静 */
    private val smooth = Snapshot(time = 0, accMean = 0.4, accStd = 0.1, accN = 50, gyroMean = 0.05)

    private val home = WifiMemory(anchor = AP, anchorLat = LAT, anchorLon = LON, anchorAcc = 8.0)

    /** 随身 Wi-Fi：名字、计费、网段都不像热点，和家里路由器一样当锚 */
    private fun Snapshot.onAp(at: Long, link: Int? = null) = copy(
        time = at, net = Snapshot.Net.WIFI, wifiBssid = AP, wifiSsid = "CAR-8899", wifiRssi = -50, wifiMetered = false,
        wifiGateway = "192.168.1.1", netLink = link,
    )

    private fun gps(time: Long, north: Double, accuracy: Double = 8.0, speed: Double = 0.0) = Position(
        time = time, latitude = lat(north), longitude = LON, accuracy = accuracy, speed = speed, provider = Provider.GPS,
    )

    @Test
    fun manualSendDoesNotUseAPointWithAKilometresWideError() {
        // 1：屋里开卫星给的点误差 5 公里，普通上报已经丢掉，App 里「发送位置」也不能把它当主坐标
        val judge = Judge(home)
        judge.onSnapshot(T0, sit.onAp(T0))
        val sent = judge.current(gps(T0, north = 3_000.0, accuracy = 5_000.0))
        assertNull(sent.latitude, "误差 5 公里的卫星点不当主坐标")
        assertNull(sent.provider)
        assertNull(sent.netLat)
        assertEquals("zone", sent.state, "状态照带")
        assertEquals(lat(10.0), judge.current(gps(T0, north = 10.0, accuracy = 8.0)).latitude, "好的卫星点照常当主坐标")
        assertEquals(lat(10.0), judge.current(gps(T0, north = 10.0, accuracy = 120.0)).latitude, "半遮的（≤300 米）照常")
    }

    @Test
    fun manualSendDropsTheWifiEstimateOfAFollowedWifiButKeepsTheCellOne() {
        // 9：这个 Wi-Fi 量出来跟着人走过，高德按它的 MAC 查出来的是它原来所在的地方；手动发送和自动上报一样不用，基站估算照用
        val judge = Judge(WifiMemory(moving = listOf(AP)))
        judge.onSnapshot(T0, sit.onAp(T0))
        val wifi = judge.current(Position(time = T0, latitude = lat(5_000.0), longitude = LON, accuracy = 30.0, provider = Provider.WIFI))
        assertNull(wifi.netLat, "跟着走过的 Wi-Fi 的估算不报")
        assertNull(wifi.netSource)
        assertEquals("zone", wifi.state)
        val cell = judge.current(Position(time = T0, latitude = lat(800.0), longitude = LON, accuracy = 500.0, provider = Provider.CELL))
        assertEquals(lat(800.0), cell.netLat, "基站估算照报")
        assertEquals(Provider.CELL, cell.netSource)
    }

    /**
     * 2：随身 Wi-Fi 一直连着，车已经开到锚点 3 公里外，运动判断还是「附近活动」；网络估算太远开了卫星量。同一个 8 米精度的点
     * 被动定位先送进快照、还是定位回调先到，都要马上把它当主坐标报出去
     */
    private fun probeFarPoint(snapshotFirst: Boolean): List<Position> {
        val judge = Judge(home)
        val reports = mutableListOf<Position>()
        fun take(d: Decision?) {
            d?.report?.let { reports += judge.describe(it) }
        }
        take(judge.onSnapshot(T0, sit.onAp(T0)))
        // 进屋里走动时取的网络估算在 3 公里外：开卫星量
        take(judge.onFetched(T0 + 500, Position(time = T0 + 500, latitude = lat(3_000.0), longitude = LON, accuracy = 100.0, provider = Provider.CELL)))
        assertTrue(judge.gps, "估算太远，开卫星量")
        val t = T0 + 2_000
        val far = gps(t - 500, north = 3_000.0)
        val passive = Snapshot.Fix(Provider.GPS, far.time, far.latitude, far.longitude, far.accuracy, far.speed)
        if (snapshotFirst) {
            take(judge.onSnapshot(t, sit.onAp(t).copy(fix = passive)))
            take(judge.onFix(t, far))
        } else {
            take(judge.onFix(t, far))
            take(judge.onSnapshot(t, sit.onAp(t).copy(fix = passive)))
        }
        return reports
    }

    @Test
    fun aFarProbePointIsReportedWhicheverWayItArrivesFirst() {
        for (snapshotFirst in listOf(false, true)) {
            val reports = probeFarPoint(snapshotFirst)
            val main = reports.filter { it.provider == Provider.GPS }
            assertEquals(1, main.size, "${if (snapshotFirst) "快照" else "定位回调"}先到：报出远处的卫星点一次，实际 $reports")
            assertEquals(lat(3_000.0), main.single().latitude)
        }
    }

    /** 开着卫星量：锚 Wi-Fi 连着、屋里走动，网络估算在 3 公里外 */
    private fun probing(judge: Judge, reports: MutableList<Position>) {
        fun take(d: Decision?) {
            d?.report?.let { reports += judge.describe(it) }
        }
        take(judge.onSnapshot(T0, sit.onAp(T0)))
        take(judge.onFetched(T0 + 500, Position(time = T0 + 500, latitude = lat(3_000.0), longitude = LON, accuracy = 100.0, provider = Provider.CELL)))
        assertTrue(judge.gps)
    }

    @Test
    fun aFarPointOnlyThePassiveListenerSawIsReportedToo() {
        // 2：远处的卫星点只从被动定位进了快照（别的 App 开的卫星，或者定位回调还没来）：快照这边也先比完再挪锚
        val judge = Judge(home)
        val reports = mutableListOf<Position>()
        probing(judge, reports)
        val t = T0 + 2_000
        val far = gps(t - 500, north = 3_000.0)
        judge.onSnapshot(t, sit.onAp(t).copy(fix = Snapshot.Fix(Provider.GPS, far.time, far.latitude, far.longitude, 8.0, 0.0)))
            .report?.let { reports += judge.describe(it) }
        assertEquals(listOf(lat(3_000.0)), reports.filter { it.provider == Provider.GPS }.map { it.latitude })
    }

    @Test
    fun theProbeComparesWithTheAnchorPointItStartedFrom() {
        // 2：量的过程中先来一个说不清的点（离锚点 150 米，Wi-Fi 记忆把锚点挪了过去），再来一个离原锚点 330 米的：
        // 和开始时的锚点比已经超出覆盖范围，要报；和挪过的锚点比（180 米）会被当成说不清
        val judge = Judge(home)
        val reports = mutableListOf<Position>()
        probing(judge, reports)
        var t = T0
        for (north in listOf(150.0, 330.0)) {
            t += 2_000
            val p = gps(t - 500, north)
            judge.onFix(t, p)?.report?.let { reports += judge.describe(it) }
            judge.onSnapshot(t, sit.onAp(t)).report?.let { reports += judge.describe(it) }
            if (north == 150.0) {
                assertTrue(reports.none { it.provider == Provider.GPS }, "150 米说不清，不报")
                assertEquals(lat(150.0), judge.wifiMemory.anchorLat, "锚点跟着挪了")
            }
        }
        assertEquals(listOf(lat(330.0)), reports.filter { it.provider == Provider.GPS }.map { it.latitude })
    }

    /**
     * 3：在家开着随身 Wi-Fi 一边晃一边量过（还在这里，冷却 10 分钟）；随后两份快照之间它断开又连上（同一个 BSSID），
     * 断开前的冷却不该再挡：接着在车上晃，马上能再量。没断开的对照组冷却照挡
     */
    private fun gpsOnAfterCooldown(reconnect: Boolean): Long? {
        val judge = Judge(home)
        var t = T0
        var link = 1
        // 车上晃着、还没开：坐车判出来、累计满 60 秒就开卫星量；量到的点都在锚点上，量满 90 秒算还在这里，冷却
        var measured = false
        while (!measured || judge.gps) {
            t += 2_000
            if (judge.gps) {
                judge.onFix(t, gps(t - 500, north = 0.0))
                measured = true
            }
            judge.onSnapshot(t, ride.onAp(t, link))
            check(t - T0 < 600_000) { "10 分钟内没量完" }
        }
        if (reconnect) link++
        val back = t
        while (t - back < 300_000) {
            t += 2_000
            judge.onSnapshot(t, ride.onAp(t, link))
            if (judge.gps) return t - back
        }
        return null
    }

    @Test
    fun reconnectingTheSameWifiBetweenSnapshotsClearsTheCooldown() {
        val after = gpsOnAfterCooldown(reconnect = true)
        assertNotNull(after, "断开重连过，5 分钟都没再量")
        assertTrue(after <= 10_000, "断开重连后 ${after / 1000} 秒才再量")
        assertNull(gpsOnAfterCooldown(reconnect = false), "一直连着的冷却照挡（对照）")
    }

    @Test
    fun settingTheClockADayFastDoesNotStopADrive() {
        // 6：平稳行车 12 m/s，被动定位和定位回调都有点。走到一半系统时间被改快一天（卫星时间照常），引擎量出来告诉判断层；
        // 之后照常开着卫星、每 10 秒报新卫星点
        val judge = Judge()
        val day = 86_400_000L
        var wall = T0
        var elapsed = 3_600_000L
        var sat = T0
        var north = 0.0
        val reports = mutableListOf<Pair<Long, Position>>()
        fun take(d: Decision?) {
            d?.report?.let { reports += wall to judge.describe(it) }
        }
        fun drive(seconds: Int) = repeat(seconds / 2) {
            wall += 2_000
            elapsed += 2_000
            sat += 2_000
            north += 24.0
            val fix = gps(sat - 500, north, speed = 12.0)
            if (judge.gps) take(judge.onFix(wall, fix, elapsed - 500))
            val passive = Snapshot.Fix(Provider.GPS, fix.time, fix.latitude, fix.longitude, 8.0, 12.0, elapsed = elapsed - 500)
            take(judge.onSnapshot(wall, smooth.copy(time = wall, elapsed = elapsed, fix = passive)))
        }
        drive(120)
        assertEquals(Motion.VEHICLE, judge.motion)
        assertTrue(judge.gps)
        wall += day
        judge.clockShifted(day)
        val jumped = wall
        drive(600)
        assertTrue(judge.gps, "改钟后卫星照开")
        assertEquals(Motion.VEHICLE, judge.motion)
        val fresh = reports.filter { it.first >= jumped && it.second.provider == Provider.GPS }.map { it.second.time }
        assertTrue(fresh.size >= 55, "改钟后 10 分钟只报了 ${fresh.size} 个卫星点")
        assertEquals(fresh.size, fresh.toSet().size, "都是新点，不是重报改钟前那一个")
    }

    @Test
    fun aReliableFastSatellitePointGetsOutOfStill() {
        // 7：从保存的静止状态起来，一直很安静（平稳的高铁），每份快照都有 8 米精度、12 m/s 的新鲜被动卫星点：要转坐车、开卫星报
        val classifier = MotionClassifier().apply { seedStill() }
        var t = T0
        val out = (0 until 151).map {
            t += 2_000
            classifier.update(still.copy(time = t, fix = Snapshot.Fix(Provider.GPS, t - 500, accuracy = 8.0, speed = 12.0)))
        }
        assertEquals(Motion.VEHICLE, out.first(), "第一份就出静止")
        assertTrue(out.all { it == Motion.VEHICLE })

        val judge = Judge(restored = Motion.STILL)
        val reports = mutableListOf<Position>()
        t = T0
        var north = 0.0
        repeat(151) {
            t += 2_000
            north += 24.0
            val fix = gps(t - 500, north, speed = 12.0)
            if (judge.gps) judge.onFix(t, fix)?.report?.let { reports += judge.describe(it) }
            val passive = Snapshot.Fix(Provider.GPS, fix.time, fix.latitude, fix.longitude, 8.0, 12.0)
            judge.onSnapshot(t, still.copy(time = t, fix = passive)).report?.let { reports += judge.describe(it) }
        }
        assertEquals(Motion.VEHICLE, judge.motion)
        assertTrue(reports.count { it.provider == Provider.GPS } >= 25, "5 分钟只报了 ${reports.count { it.provider == Provider.GPS }} 个卫星点")
    }

    @Test
    fun aBadSatellitePointStillCannotWakeFromStill() {
        // 7 的反面：误差几百米的点带的高速不可信，静止照旧
        val classifier = MotionClassifier().apply { seedStill() }
        var t = T0
        val out = (0 until 30).map {
            t += 2_000
            classifier.update(still.copy(time = t, fix = Snapshot.Fix(Provider.GPS, t - 500, accuracy = 200.0, speed = 12.0)))
        }
        assertTrue(out.all { it == Motion.STILL }, "$out")
    }

    private companion object {
        const val AP = "3c:8c:93:20:00:01"
        const val T0 = 1_800_000_000_000L
        const val LAT = 30.0
        const val LON = 120.0

        /** 往北 [meters] 米的纬度 */
        fun lat(meters: Double) = LAT + meters / (6_371_000.0 * PI / 180)
    }
}
