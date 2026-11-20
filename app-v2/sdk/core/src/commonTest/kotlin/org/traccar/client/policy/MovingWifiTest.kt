package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.sense.Snapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 移动 Wi-Fi 快速识别（方案 docs/specs/2026-11-19-moving-wifi.md 第 6 节的单测）：像引擎一样每 2 秒给 [Judge] 一份快照，它说开卫星
 * 就（冷启动 [Phone.coldStart] 后）每 2 秒给一个卫星点，说取网络定位就给一个；记下报了什么、卫星开了几次。
 */
class MovingWifiTest {

    private enum class Act(val snapshot: Snapshot) {
        /** 坐着不动、等红灯：几乎不晃但陀螺略动（不到静止） */
        SIT(Snapshot(time = 0, accMean = 0.4, accStd = 0.02, accN = 50, gyroMean = 0.03)),

        /** 屋里拿着玩、坐着动 */
        FIDGET(Snapshot(time = 0, accMean = 0.4, accStd = 0.3, accN = 50, gyroMean = 0.08)),

        /** 口袋里走路、跑 */
        WALK(Snapshot(time = 0, accMean = 2.5, accStd = 6.0, accN = 50, gyroMean = 2.0)),

        /** 车里一直晃、没走路节奏 */
        RIDE(Snapshot(time = 0, accMean = -0.2, accStd = 1.2, accN = 50, gyroMean = 0.3)),
    }

    private class Phone(memory: WifiMemory = WifiMemory()) {
        var judge = Judge(memory)
        var time = 1_000_000_000L
        var north = 0.0
        var bssid: String? = null
        var ssid = "CAR-8899"

        /** 连着的基站（跟踪区固定 7）；null 是读不到（P20 没卡） */
        var cell: Long? = null

        /** 卫星开了多久才定到第一个点；null 是一直定不到 */
        var coldStart: Long? = 30_000L

        /** 卫星点在真实位置上加的误差（米，往北） */
        var gpsNoise: (Long) -> Double = { 0.0 }

        /** 取网络定位给的估算（往北多少米、精度），null 是取不到 */
        var network: Pair<Double, Double>? = null
        var networkProvider = Provider.CELL

        val reports = mutableListOf<Position>()
        val gpsStarts = mutableListOf<Long>()
        private var gpsOnSince: Long? = null

        fun run(seconds: Int, act: Act, mps: Double = 0.0) {
            repeat(seconds / 2) {
                time += 2_000
                north += mps * 2
                val since = gpsOnSince
                val cold = coldStart
                if (judge.gps && since != null && cold != null && time - since >= cold) {
                    take(judge.onFix(time, gpsAt(time - 500, mps)))
                }
                take(judge.onSnapshot(time, snapshot(act)))
                track()
            }
        }

        private fun snapshot(act: Act): Snapshot {
            val tac = cell?.let { 7 }
            val ap = bssid ?: return act.snapshot.copy(time = time, net = Snapshot.Net.CELL, cellTac = tac, cellId = cell)
            return act.snapshot.copy(
                time = time, net = Snapshot.Net.WIFI, wifiBssid = ap, wifiSsid = ssid, wifiRssi = -50, wifiMetered = false,
                wifiGateway = "192.168.1.1", cellTac = tac, cellId = cell,
            )
        }

        private fun gpsAt(t: Long, mps: Double) = Position(
            time = t, latitude = lat(north + gpsNoise(t)), longitude = LON, accuracy = 8.0, speed = mps, provider = Provider.GPS,
        )

        private fun take(d: Decision?) {
            d ?: return
            d.report?.let { reports += judge.describe(it) }
            if (d.fetch) {
                val net = network?.let { (meters, acc) ->
                    Position(time = time, latitude = lat(north + meters), longitude = LON, accuracy = acc, provider = networkProvider)
                }
                take(judge.onFetched(time + 500, net))
            }
            track()
        }

        private fun track() {
            if (judge.gps && gpsOnSince == null) {
                gpsOnSince = time
                gpsStarts += time
            } else if (!judge.gps) {
                gpsOnSince = null
            }
        }

        /** 从 [from] 起报的点 */
        fun reportsSince(from: Long) = reports.filter { it.time >= from }
    }

    @Test
    fun carWifiWaitThenDriveWithRedLightsIsOnTheRoadWithinThreeMinutes() {
        val phone = Phone()
        // 走到车旁（卫星开着），上车连上车载 Wi-Fi，卫星这时定的点把它记成锚
        phone.run(60, Act.WALK, 1.4)
        phone.bssid = CAR
        phone.run(120, Act.SIT)
        assertEquals("zone", phone.reports.last().state, "车没开，静坐 2 分钟：屋里走动")
        assertEquals(CAR, phone.judge.wifiMemory.anchor, "车载 Wi-Fi 被记成锚")
        // 开车：每开 90 秒等一次红灯（安静 40 秒）
        val drive = phone.time
        repeat(4) {
            phone.run(90, Act.RIDE, 10.0)
            phone.run(40, Act.SIT)
        }
        val moving = phone.reportsSince(drive).firstOrNull { it.state == "moving" }
        assertNotNull(moving, "开车 9 分钟一直没转在路上")
        assertTrue(moving.time - drive <= 180_000, "开车后 ${(moving.time - drive) / 1000} 秒才转在路上")
        assertEquals(Provider.GPS, moving.provider, "转在路上那一刻报的是卫星点")
        assertTrue(CAR in phone.judge.wifiMemory.moving, "记成跟着人走过（提示）")

        // 到了，停车坐着：在路上的卫星点把锚挪到这里，慢慢转屋里走动、关卫星
        phone.run(600, Act.SIT)
        assertEquals("zone", phone.reports.last().state)
        assertTrue(!phone.judge.gps)
        // 下次再开这辆车：坐车判出来以后累计 20 秒就开卫星（不是 60 秒）
        val again = phone.time
        phone.run(150, Act.RIDE, 10.0)
        val probe = phone.gpsStarts.first { it > again } - again
        assertTrue(probe in 78_000..84_000, "坐车判出（60 秒）后 20 秒就开卫星：${probe / 1000} 秒")
        assertTrue(phone.reportsSince(again).any { it.state == "moving" })
    }

    @Test
    fun walkingAroundHomeOnTheAnchorRarelyTurnsOnSatellitesAndNeverReportsThem() {
        val phone = Phone(WifiMemory(anchor = HOME, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = HOME
        phone.ssid = "HOME-5G"
        // 屋里卫星漂几十米
        phone.gpsNoise = { t -> 30.0 * sin(t / 37_000.0) }
        phone.run(60, Act.FIDGET)
        val from = phone.time
        homeHalfHour(phone)
        val reports = phone.reportsSince(from)
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.all { it.state == "zone" || it.state == "still" }, "全程屋里走动：${reports.map { it.state }.distinct()}")
        assertTrue(reports.none { it.latitude != null }, "不报主坐标")
        val starts = phone.gpsStarts.count { it >= from }
        assertTrue(starts in 1..3, "30 分钟开卫星 $starts 次")
    }

    /** 在家 30 分钟：走一会儿、坐一会儿、跑一阵 */
    private fun homeHalfHour(phone: Phone) {
        repeat(5) {
            phone.run(120, Act.WALK)
            phone.run(90, Act.FIDGET)
            phone.run(60, Act.WALK)
            phone.run(60, Act.SIT)
            phone.run(30, Act.WALK)
        }
    }

    @Test
    fun pocketWifiRiddenAwayThenUsedAtHomeAgain() {
        val phone = Phone(WifiMemory(anchor = POCKET, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = POCKET
        phone.ssid = "TP-LINK_5G_1A2B"
        phone.run(60, Act.FIDGET)
        // 揣兜里坐车走：量出来在动
        val ride = phone.time
        phone.run(300, Act.RIDE, 10.0)
        assertTrue(phone.reportsSince(ride).any { it.state == "moving" && it.provider == Provider.GPS }, "跟着坐车量出来在动")
        assertTrue(phone.judge.wifiMemory.moving.contains(POCKET))
        backHomeWithPocketWifi(phone)
    }

    @Test
    fun pocketWifiWalkedAwayThenUsedAtHomeAgain() {
        // 揣兜里走出门（1.4 m/s）：卫星第一个点 2 / 10 / 30 秒才来、这个 Wi-Fi 以前跟着走过（20 秒就量）或没有，都 3 分钟内报出卫星点。
        // 审计 5：以前第一个点还在锚附近就当「还在这里」冷却 10 分钟，走出近一公里才跟上
        for (hinted in listOf(false, true)) {
            for (cold in listOf(2_000L, 10_000L, 30_000L)) {
                val phone = Phone(
                    WifiMemory(
                        anchor = POCKET, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0,
                        moving = if (hinted) listOf(POCKET) else emptyList(),
                    ),
                )
                phone.bssid = POCKET
                phone.ssid = "TP-LINK_5G_1A2B"
                phone.coldStart = cold
                phone.run(60, Act.FIDGET)
                val walk = phone.time
                phone.run(900, Act.WALK, 1.4)
                val case = "首点 ${cold / 1000} 秒、${if (hinted) "跟着走过" else "第一次"}"
                val moving = phone.reportsSince(walk).firstOrNull { it.state == "moving" && it.provider == Provider.GPS }
                assertNotNull(moving, "$case：走 15 分钟一直没量出来在动")
                assertTrue(moving.time - walk <= 180_000, "$case：走了 ${(moving.time - walk) / 1000} 秒才量出来")
                assertTrue(phone.judge.wifiMemory.moving.contains(POCKET))
                if (!hinted && cold == 30_000L) backHomeWithPocketWifi(phone)
            }
        }
    }

    /** 带回（另一个）家，连着它在家里待着：重新当锚，屋里走动不开卫星（冷却后最多偶尔量一下），不报主坐标 */
    private fun backHomeWithPocketWifi(phone: Phone) {
        phone.run(30, Act.WALK, 1.4)
        phone.run(240, Act.SIT)
        assertEquals("zone", phone.reports.last().state)
        val anchor = assertNotNull(phone.judge.wifiMemory.anchorLat, "回家后重新当锚")
        assertEquals(POCKET, phone.judge.wifiMemory.anchor)
        assertTrue(anchor > lat(500.0), "锚在新的地方")
        phone.gpsNoise = { t -> 25.0 * cos(t / 41_000.0 * PI) }
        val from = phone.time
        homeHalfHour(phone)
        val reports = phone.reportsSince(from)
        assertTrue(reports.all { it.state != "moving" }, "回家后屋里走动：${reports.map { it.state }.distinct()}")
        assertTrue(reports.none { it.latitude != null }, "不报主坐标")
        assertTrue(phone.gpsStarts.count { it >= from } <= 3, "30 分钟开卫星 ${phone.gpsStarts.count { it >= from }} 次")
    }

    @Test
    fun noFixWithinNinetySecondsKeepsTheStateAndRetriesAfterFiveMinutes() {
        val phone = Phone(WifiMemory(anchor = HOME, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = HOME
        phone.coldStart = null
        phone.run(60, Act.FIDGET)
        val from = phone.time
        phone.run(600, Act.WALK)
        val starts = phone.gpsStarts.filter { it >= from }
        assertTrue(starts.size >= 2, "开了 ${starts.size} 次")
        assertTrue(phone.reportsSince(from).all { it.state == "zone" }, "量不出来不下结论")
        val gap = starts[1] - starts[0]
        assertTrue(gap in 390_000..400_000, "开 90 秒没定到，关掉 5 分钟后再量：两次间隔 ${gap / 1000} 秒")
    }

    @Test
    fun cooldownOfTheHomeAnchorDoesNotBlockTheCarWifi() {
        // 在家量过一次、判在家（家里 Wi-Fi 冷却 10 分钟），接着出门上车连上车载 Wi-Fi（新锚）：冷却只挡家里那个，车上照样 3 分钟内转在路上
        val phone = Phone(WifiMemory(anchor = HOME, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = HOME
        phone.run(60, Act.SIT)
        phone.run(120, Act.WALK)
        phone.run(90, Act.SIT) // 一边走一边量不凭一个近点下结论，停下来（走路判断还会撑 1 分钟）才算在家
        assertEquals(1, phone.gpsStarts.size, "在家量了一次")
        assertTrue(!phone.judge.gps, "量过了，在家，冷却")
        phone.bssid = null
        phone.run(60, Act.WALK, 1.4)
        phone.bssid = CAR
        phone.run(120, Act.SIT)
        assertEquals(CAR, phone.judge.wifiMemory.anchor, "车载 Wi-Fi 被记成锚")
        val drive = phone.time
        repeat(2) {
            phone.run(90, Act.RIDE, 10.0)
            phone.run(40, Act.SIT)
        }
        val moving = phone.reportsSince(drive).firstOrNull { it.state == "moving" }
        assertNotNull(moving, "家里 Wi-Fi 的冷却挡住了车载 Wi-Fi 的探测")
        assertTrue(moving.time - drive <= 180_000, "开车后 ${(moving.time - drive) / 1000} 秒才转在路上")
    }

    @Test
    fun clockSetBackKeepsTheCooldownRhythm() {
        // 量过一次、人在家（冷却 10 分钟），系统时间被往回改了一天：引擎量出来告诉判断层，冷却照样 10 分钟左右结束，不是一天不量
        val phone = Phone(WifiMemory(anchor = HOME, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = HOME
        phone.run(60, Act.SIT)
        phone.run(120, Act.WALK)
        phone.run(90, Act.SIT)
        assertEquals(1, phone.gpsStarts.size)
        assertTrue(!phone.judge.gps, "量过了，在家")
        phone.time -= DAY
        phone.judge.clockShifted(-DAY)
        val before = phone.gpsStarts.size
        phone.run(900, Act.WALK)
        assertEquals(1, phone.gpsStarts.size - before, "冷却完照样再量一次")
    }

    @Test
    fun networkEstimateFarFromTheAnchorProbesEvenWhenNotMoving() {
        val phone = Phone(WifiMemory(anchor = CAR, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = CAR
        phone.run(60, Act.SIT)
        assertTrue(phone.gpsStarts.isEmpty())
        // 运动判断说没在动（车很平稳、人坐着），屋里走动 5 分钟取一次网络定位：基站估算在 3 公里外
        phone.network = 3_000.0 to 800.0
        phone.run(300, Act.SIT)
        assertEquals(1, phone.gpsStarts.size, "估算离锚点 3 公里：量一次")
        // 人真在 3 公里外（车开得平顺、坐着不动）：量到 8 米精度、速度 0 的卫星点，马上当主坐标报出去，状态不变（审计 1）
        val far = Phone(WifiMemory(anchor = CAR, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        far.bssid = CAR
        far.north = 3_000.0
        far.run(60, Act.SIT)
        far.network = 0.0 to 800.0
        far.run(300, Act.SIT)
        val probed = far.gpsStarts.single()
        val point = far.reportsSince(probed).firstOrNull { it.latitude != null }
        assertNotNull(point, "量出离锚点 3 公里，主坐标却没报")
        assertTrue(point.time - probed <= 32_000, "开卫星后 ${(point.time - probed) / 1000} 秒才报")
        assertEquals(Provider.GPS, point.provider)
        assertEquals(lat(3_000.0), assertNotNull(point.latitude), 1e-6)
        assertEquals("zone", point.state, "运动判断没说在动，状态不变")
        assertTrue(!far.judge.gps, "量完关卫星")

        // 估算就在附近：不量
        val near = Phone(WifiMemory(anchor = CAR, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        near.bssid = CAR
        near.network = 600.0 to 800.0
        near.run(400, Act.SIT)
        assertTrue(near.gpsStarts.isEmpty())
    }

    @Test
    fun unfamiliarCellOnTheAnchorProbesOnce() {
        val phone = Phone(WifiMemory(anchor = HOME, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0, anchorCells = listOf("7-1")))
        phone.bssid = HOME
        phone.run(60, Act.SIT)
        assertTrue(phone.gpsStarts.isEmpty(), "快照里读不到基站（P20 没卡）：不量")
        val judge = phone.judge
        var t = phone.time
        fun feed(cell: Long) {
            t += 2_000
            judge.onSnapshot(t, Act.FIDGET.snapshot.copy(time = t, net = Snapshot.Net.WIFI, wifiBssid = HOME, cellTac = 7, cellId = cell))
        }
        repeat(5) { feed(1) }
        assertTrue(!judge.gps, "熟悉的基站")
        feed(2)
        assertTrue(judge.gps, "锚这里没见过的基站：量一次")
        // 量出来就在这里：记下这个基站，以后在两个基站间来回切不再量
        assertEquals(null, judge.onFix(t + 1_000, Position(time = t + 1_000, latitude = lat(10.0), longitude = LON, accuracy = 8.0, provider = Provider.GPS))?.report)
        assertTrue(!judge.gps)
        assertTrue("7-2" in judge.wifiMemory.anchorCells)
    }

    @Test
    fun cooldownAtHomeDoesNotDelayLeavingSignals() {
        // 审计 2：在家量过、冷却中，不断开直接带着随身 Wi-Fi 上车开走。出现锚这里没见过的基站（离开信号）不受 10/20/30 分钟冷却限制：5 分钟内跟上
        val phone = Phone(WifiMemory(anchor = POCKET, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0, anchorCells = listOf("7-1")))
        phone.bssid = POCKET
        phone.cell = 1
        phone.run(60, Act.SIT)
        phone.run(120, Act.WALK)
        phone.run(90, Act.SIT)
        assertEquals(1, phone.gpsStarts.size, "在家量了一次")
        assertTrue(!phone.judge.gps, "在家，冷却 10 分钟")
        phone.run(60, Act.WALK, 1.4)
        val drive = phone.time
        repeat(30) {
            phone.run(10, Act.RIDE, 10.0)
            if (phone.north > 1_000.0) phone.cell = 2
        }
        val moving = phone.reportsSince(drive).firstOrNull { it.state == "moving" && it.provider == Provider.GPS }
        assertNotNull(moving, "冷却挡住了基站这条离开信号")
        assertTrue(moving.time - drive <= 300_000, "开车后 ${(moving.time - drive) / 1000} 秒才跟上")
    }

    @Test
    fun reconnectingTheSameWifiElsewhereClearsTheCooldown() {
        // 审计 2：在家接连量了几次（冷却 30 分钟），断开随身 Wi-Fi、走到车旁、重连同一个，在车旁记下新锚点：冷却是对着家里那个锚点的，车开起来照样 3 分钟内跟上
        val phone = Phone(WifiMemory(anchor = POCKET, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = POCKET
        phone.run(60, Act.SIT)
        // 冷却 10、20 分钟各等过去，第三次之后冷却 30 分钟
        repeat(3) {
            phone.run(120, Act.WALK)
            phone.run(90, Act.SIT)
            if (it < 2) phone.run(1_200, Act.SIT)
        }
        assertEquals(3, phone.gpsStarts.size, "在家量了三次")
        assertTrue(!phone.judge.gps)
        phone.run(120, Act.WALK)
        phone.run(90, Act.SIT)
        assertEquals(3, phone.gpsStarts.size, "第三次之后冷却 30 分钟")
        phone.bssid = null
        phone.run(120, Act.WALK, 1.4)
        phone.bssid = POCKET
        phone.run(120, Act.SIT)
        val anchorLat = assertNotNull(phone.judge.wifiMemory.anchorLat)
        assertTrue(anchorLat > lat(100.0), "车旁记下新锚点")
        val drive = phone.time
        repeat(2) {
            phone.run(90, Act.RIDE, 10.0)
            phone.run(40, Act.SIT)
        }
        val moving = phone.reportsSince(drive).firstOrNull { it.state == "moving" }
        assertNotNull(moving, "家里的冷却挡住了车旁重连的同一个 Wi-Fi")
        assertTrue(moving.time - drive <= 180_000, "开车后 ${(moving.time - drive) / 1000} 秒才转在路上")
    }

    @Test
    fun wifiEstimatesUnderAFollowedWifiAreDropped() {
        // 审计 6：这个 Wi-Fi 跟着人走过，高德按它的 MAC 查到的是它原来所在的地方（3 公里外）：Wi-Fi 定位不用，基站估算照用
        fun zoneNet(followed: Boolean, provider: String): Double? {
            val phone = Phone(WifiMemory(moving = if (followed) listOf(POCKET) else emptyList()))
            phone.bssid = POCKET
            phone.network = 3_000.0 to 30.0
            phone.networkProvider = provider
            phone.run(20, Act.FIDGET)
            val zone = phone.reports.last()
            assertEquals("zone", zone.state)
            return zone.netLat
        }
        assertEquals(null, zoneNet(followed = true, Provider.WIFI), "跟着走过的 Wi-Fi 下，Wi-Fi 定位不用")
        assertNotNull(zoneNet(followed = true, Provider.CELL), "基站估算照用")
        assertNotNull(zoneNet(followed = false, Provider.WIFI), "没跟着走过的 Wi-Fi 下照用")
        // 连续定位来的也一样
        val phone = Phone(WifiMemory(moving = listOf(POCKET)))
        phone.bssid = POCKET
        phone.run(20, Act.FIDGET)
        val t = phone.time + 500
        val wifi = Position(time = t, latitude = lat(3_000.0), longitude = LON, accuracy = 30.0, provider = Provider.WIFI)
        assertEquals(null, phone.judge.onFix(t, wifi), "连续定位来的 Wi-Fi 定位丢掉")
    }

    @Test
    fun sensorsStayAwakeWhileProbing() {
        // 审计 8：量的时候转了静止。传感器睡了的话探测的时限没人查，屋里又定不到星，卫星一直开到下一次呼吸
        val phone = Phone(WifiMemory(anchor = HOME, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0, anchorCells = listOf("7-1")))
        phone.bssid = HOME
        phone.cell = 1
        phone.coldStart = null
        phone.run(60, Act.SIT)
        val quiet = Act.SIT.snapshot.copy(accStd = 0.007, gyroMean = 0.001)
        val judge = phone.judge
        var t = phone.time
        fun feed(cell: Long) {
            t += 2_000
            judge.onSnapshot(t, quiet.copy(time = t, net = Snapshot.Net.WIFI, wifiBssid = HOME, cellTac = 7, cellId = cell))
        }
        repeat(30) { feed(1) }
        feed(2)
        assertTrue(judge.gps, "锚这里没见过的基站：量一次")
        val probing = t
        while (judge.motion != org.traccar.client.motion.Motion.STILL) feed(2)
        assertTrue(judge.gps && !judge.sleeping, "还在量：转了静止也先不睡")
        while (judge.gps) feed(2)
        assertTrue(t - probing <= AnchorProbe.LIMIT + 2_000, "卫星开了 ${(t - probing) / 1000} 秒")
        assertTrue(judge.sleeping, "量完了再睡")
    }

    @Test
    fun aPoorIndoorPointWithSpeedIsNotADeparture() {
        // 审计 3（第二批卫星点分档已修，这里照审计原样复现）：离锚点 400 米、标称精度 500 米、速度 4 m/s 的单点，不判坐车、不清锚
        val phone = Phone(WifiMemory(anchor = HOME, anchorLat = lat(0.0), anchorLon = LON, anchorAcc = 10.0))
        phone.bssid = HOME
        phone.run(60, Act.FIDGET)
        val judge = phone.judge
        val t = phone.time + 500
        judge.onFetched(t, Position(time = t, latitude = lat(3_000.0), longitude = LON, accuracy = 300.0, provider = Provider.CELL))
        assertTrue(judge.gps, "在量")
        // 500 米精度是审计原样；150 米精度放在 300 米外（减掉精度还在 200 米内，说明不了离开）
        for ((meters, accuracy) in listOf(400.0 to 500.0, 300.0 to 150.0)) {
            val d = judge.onFix(t + 1_000, Position(time = t + 1_000, latitude = lat(meters), longitude = LON, accuracy = accuracy, speed = 4.0, provider = Provider.GPS))
            assertEquals(null, d?.report, "精度 $accuracy 米：不报")
            assertEquals("zone", judge.current(Position(time = t)).state, "精度 $accuracy 米：不转在路上")
            assertEquals(HOME, judge.wifiMemory.anchor, "精度 $accuracy 米：锚还在")
            assertTrue(judge.wifiMemory.moving.isEmpty(), "精度 $accuracy 米：不记成跟着走")
        }
    }

    @Test
    fun anchorPlaceSurvivesARestart() {
        val before = Phone()
        before.bssid = HOME
        before.run(30, Act.WALK, 1.4)
        before.run(150, Act.SIT)
        val memory = before.judge.wifiMemory
        assertNotNull(memory.anchorLat)
        // 进程重启：判断层从头来，Wi-Fi 记忆（带锚点位置）从状态库接着用。揣着这个 Wi-Fi 坐车走，照样量得出离开了
        val after = Phone(memory)
        after.north = before.north
        after.bssid = HOME
        after.run(60, Act.FIDGET)
        val ride = after.time
        after.run(300, Act.RIDE, 10.0)
        assertTrue(after.reportsSince(ride).any { it.state == "moving" && it.provider == Provider.GPS })
    }

    private companion object {
        const val DAY = 86_400_000L
        const val CAR = "3c:aa:bb:cc:dd:01"
        const val HOME = "3c:8c:93:10:00:30"
        const val POCKET = "c8:3a:35:12:34:56"
        const val LAT = 30.2741
        const val LON = 120.1551

        fun lat(northMeters: Double) = LAT + northMeters / 111_320.0
    }
}
