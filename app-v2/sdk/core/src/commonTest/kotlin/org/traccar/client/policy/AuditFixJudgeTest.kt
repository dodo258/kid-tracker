package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.motion.Motion
import org.traccar.client.motion.MotionClassifier
import org.traccar.client.sense.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 审计第二批（docs/specs/2026-11-19-v2-audit-fixes.md）判断层几条：高 10、高 8、高 9、中 4、屋里的卫星点分档 */
class AuditFixJudgeTest {

    private val swing = Snapshot(time = 0, accMean = 2.5, accStd = 6.0, accN = 50, gyroMean = 2.0)
    private val still = Snapshot(time = 0, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001)
    private val fidget = Snapshot(time = 0, accMean = 0.4, accStd = 0.3, accN = 50, gyroMean = 0.08)

    /** 车上平稳放着：不算安静（0.1 > 0.05），也不算有动静（< 0.15） */
    private val smooth = Snapshot(time = 0, accMean = 0.4, accStd = 0.1, accN = 50, gyroMean = 0.05)

    private fun gps(time: Long, speed: Double = 0.0, accuracy: Double = 8.0) =
        Position(latitude = 30.0, longitude = 120.0, accuracy = accuracy, time = time, speed = speed, provider = Provider.GPS)

    @Test
    fun clockSetFastNetworkPointDoesNotBlockLaterSatellitePoints() {
        // 高 10：手机时间调快一天，网络定位跟着手机时间、卫星点用卫星时间。先报一个网络点，之后的卫星点照样报
        val policy = ReportPolicy()
        val day = 86_400_000L
        val reports = mutableListOf<Outgoing>()
        fun take(d: Decision) { d.report?.let { reports += it } }
        take(policy.onMotion(0, Motion.WALK))
        take(policy.onFix(31_000, Position(latitude = 30.0, longitude = 120.0, accuracy = 40.0, time = 31_000 + day, provider = Provider.WIFI)))
        assertEquals("net", reports.last().reason, "卫星 30 秒没定到，先报网络点")
        for (t in 40L..100L) take(policy.onFix(t * 1000, gps(t * 1000)))
        val satellite = reports.filter { it.fix?.provider == Provider.GPS }
        assertTrue(satellite.size >= 5, "之后 1 分钟每 10 秒报一个卫星点：${satellite.size}")
    }

    @Test
    fun lateSatellitePointWithinOneBatchStillCounts() {
        // 高 8：息屏时快照 10 秒一批交，手里的卫星点比这一批前几窗都晚。晚一批以内算新鲜，车速照样用
        val window = smooth.copy(time = 1_000_000L, fix = Snapshot.Fix("gps", 1_007_000L, speed = 9.0))
        assertNotNull(window.freshGps(MotionClassifier.GPS_FRESH_MILLIS), "晚 7 秒")
        assertNull(window.copy(fix = window.fix!!.copy(time = 1_011_000L)).freshGps(MotionClassifier.GPS_FRESH_MILLIS), "晚一批以上不算")

        val classifier = MotionClassifier()
        var batchEnd = 1_000_000L
        val out = mutableListOf<Motion>()
        repeat(12) {
            batchEnd += 10_000
            val fix = Snapshot.Fix("gps", batchEnd - 500, accuracy = 8.0, speed = 9.0)
            // 一批 5 窗，结束时间分别比交付早 9、7、5、3、1 秒，最新的卫星点只比交付早 0.5 秒
            for (back in listOf(9_000L, 7_000L, 5_000L, 3_000L, 1_000L)) out += classifier.update(smooth.copy(time = batchEnd - back, fix = fix))
        }
        assertTrue(out.all { it == Motion.VEHICLE }, "每窗都认得车速：$out")
    }

    @Test
    fun quietPhoneInASmoothCarWithFastSatelliteIsNotStill() {
        // 高 9：手机稳放在平顺的车上，每窗都有新鲜的 12 m/s 卫星点：一直是坐车，不因为安静 2 分钟就判静止
        val classifier = MotionClassifier()
        var t = 1_000_000L
        val out = (0 until 150).map {
            t += 2_000
            classifier.update(still.copy(time = t, accStd = 0.01, gyroMean = 0.005, fix = Snapshot.Fix("gps", t - 1_000, accuracy = 8.0, speed = 12.0)))
        }
        assertTrue(out.all { it == Motion.VEHICLE }, "第 ${out.indexOfFirst { it != Motion.VEHICLE } * 2 + 2} 秒变成 ${out.firstOrNull { it != Motion.VEHICLE }}")
        // 车停了、卫星点没了，照常 2 分钟后静止
        val parked = (0 until 70).map {
            t += 2_000
            classifier.update(still.copy(time = t))
        }
        assertEquals(Motion.STILL, parked.last())
    }

    @Test
    fun indoorSatellitePointWithHugeErrorIsNotSpeedNorMainCoordinate() {
        // P20 11-18 20:15 屋里开着卫星，系统给的点误差 5.8 公里、带 4 m/s 速度：不判坐车
        val classifier = MotionClassifier()
        var t = 1_000_000L
        val out = (0 until 10).map {
            t += 2_000
            classifier.update(fidget.copy(time = t, fix = Snapshot.Fix("gps", t - 1_000, accuracy = 5_807.0, speed = 4.0)))
        }
        assertTrue(out.none { it == Motion.VEHICLE }, "$out")
        // 50～300 m 半遮的点也不拿单点速度判坐车
        val partial = MotionClassifier()
        assertTrue((0 until 10).map {
            t += 2_000
            partial.update(fidget.copy(time = t, fix = Snapshot.Fix("gps", t - 1_000, accuracy = 150.0, speed = 4.0)))
        }.none { it == Motion.VEHICLE })

        // 在路上时：被挡的点不当主坐标报，半遮的照样报
        val judge = Judge()
        t = 2_000_000L
        repeat(10) { t += 2_000; judge.onSnapshot(t, swing.copy(time = t)) }
        assertEquals(Motion.WALK, judge.motion)
        val reported = mutableListOf<Position>()
        for (i in 1..30) {
            t += 1_000
            judge.onFix(t, gps(t, speed = 4.0, accuracy = 5_807.0))?.report?.let { reported += judge.describe(it) }
        }
        assertTrue(reported.none { it.latitude != null }, "5.8 km 误差的点不报主坐标：$reported")
        var partialReported: Position? = null
        for (i in 1..12) {
            t += 1_000
            judge.onFix(t, gps(t, accuracy = 150.0))?.report?.let { partialReported = judge.describe(it) }
        }
        assertEquals(150.0, assertNotNull(partialReported).accuracy, "150 m 的点在路上照样当主坐标")
    }

    @Test
    fun sensorsStoppingWhileWalkingFallsBackToZone() {
        // 中 4：走着走着传感器不交快照了，卫星点还一秒一个：3 分钟后降成在附近活动、关卫星，不一直按走路报
        val judge = Judge()
        var t = 1_000_000L
        repeat(10) { t += 2_000; judge.onSnapshot(t, swing.copy(time = t)) }
        assertEquals(Motion.WALK, judge.motion)
        assertTrue(judge.gps)
        repeat(Judge.SENSORS_STALE_MILLIS.toInt() / 1000 + 20) { t += 1_000; judge.onFix(t, gps(t)) }
        assertEquals(Motion.FIDGET, judge.motion)
        assertFalse(judge.gps, "关卫星")
        // 快照又来了：照常判断
        repeat(5) { t += 2_000; judge.onSnapshot(t, swing.copy(time = t)) }
        assertEquals(Motion.WALK, judge.motion)
    }

    @Test
    fun noSnapshotsWhileStillIsNormal() {
        // 静止时引擎关了加速度，几小时没快照是正常的：心跳不降级
        val judge = Judge()
        var t = 1_000_000L
        repeat(70) { t += 2_000; judge.onSnapshot(t, still.copy(time = t)) }
        assertEquals(Motion.STILL, judge.motion)
        judge.onTick(t + 3_600_000)
        assertEquals(Motion.STILL, judge.motion)
    }

    @Test
    fun probeDoesNotTrustTheSpeedOfAPoorIndoorPoint() {
        // 连着锚 Wi-Fi 在屋里探测时，一个 150 m 精度、带 4 m/s 的点不算在坐车（以前直接清锚、转在路上）
        val home = Snapshot(
            time = 0, accMean = 0.4, accStd = 0.3, accN = 50, gyroMean = 0.08,
            net = Snapshot.Net.WIFI, wifiBssid = "home-ap", wifiSsid = "Home", wifiMetered = false,
        )
        val judge = Judge(WifiMemory(anchor = "home-ap", anchorLat = 30.0, anchorLon = 120.0, anchorAcc = 10.0))
        var t = 1_000_000L
        repeat(5) { t += 2_000; judge.onSnapshot(t, home.copy(time = t)) }
        assertEquals(Motion.FIDGET, judge.motion)
        // 网络估算在 3 公里外：开卫星量一量
        judge.onFetched(t, Position(latitude = 30.03, longitude = 120.0, accuracy = 40.0, time = t, provider = Provider.WIFI))
        assertTrue(judge.gps, "在量")
        val d = judge.onFix(t + 1_000, gps(t + 1_000, speed = 4.0, accuracy = 150.0))
        assertNull(d?.report)
        assertEquals(Motion.FIDGET, judge.motion, "不因为一个烂点的速度就转在路上")
        assertEquals("home-ap", judge.wifiMemory.anchor, "锚还在")
    }
}
