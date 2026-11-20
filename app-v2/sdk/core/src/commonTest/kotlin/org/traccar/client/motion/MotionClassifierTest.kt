package org.traccar.client.motion

import org.traccar.client.sense.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionClassifierTest {

    private val still = Snapshot(time = 0, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001)
    private val fidget = Snapshot(time = 0, accMean = 0.4, accStd = 0.3, accN = 50, gyroMean = 0.08)
    private val swing = Snapshot(time = 0, accMean = 2.5, accStd = 6.0, accN = 50, gyroMean = 2.0)
    private val ride = Snapshot(time = 0, accMean = -0.2, accStd = 1.2, accN = 50, gyroMean = 0.3)
    private val inHandWalk = Snapshot(time = 0, accMean = 0.4, accStd = 1.0, accN = 50, gyroMean = 0.3, accPeaks = 3)

    /** 每 2 秒喂一份，喂 [seconds] 秒，返回每份的判定 */
    private class Feed {
        val classifier = MotionClassifier()
        var time = 1_000_000L

        /** 连着不计费的 Wi-Fi 就当是上次卫星定位时连的那个（Wi-Fi 规则在 WifiBookTest） */
        fun run(seconds: Int, base: Snapshot, edit: (Snapshot) -> Snapshot = { it }): List<Motion> =
            (0 until seconds / 2).map {
                time += 2_000
                val s = edit(base.copy(time = time))
                classifier.update(s, s.wifiBssid.takeIf { s.onFixedWifi })
            }
    }

    private fun Snapshot.gps(speed: Double) = copy(fix = Snapshot.Fix("gps", time - 1_000, speed = speed))
    private fun Snapshot.wifi(bssid: String = "ap", metered: Boolean = false) = copy(net = Snapshot.Net.WIFI, wifiBssid = bssid, wifiMetered = metered)

    /** 第一个是 [m] 的是第几秒（从 2 秒起） */
    private fun List<Motion>.firstSecond(m: Motion) = indexOf(m).let { if (it < 0) -1 else (it + 1) * 2 }

    @Test
    fun stillAfterTwoQuietMinutes() {
        val f = Feed()
        val out = f.run(200, still)
        // 第一份安静快照算起满 120 秒（它本身盖着前 2 秒），所以在第 122 秒
        assertEquals(122, out.firstSecond(Motion.STILL), "连续 120 秒安静才算静止")
        assertTrue(out.take(59).all { it == Motion.FIDGET })
    }

    @Test
    fun smallBumpKeepsStillButPickingUpWakes() {
        val f = Feed()
        f.run(130, still)
        assertEquals(Motion.STILL, f.classifier.raw)
        // 夜里那种一窗 0.08 的轻震（P20 夜里最高 0.081）、陀螺略动：不醒
        assertEquals(listOf(Motion.STILL), f.run(2, still.copy(accStd = 0.08, gyroMean = 0.012)))
        assertEquals(Motion.STILL, f.run(20, still).last())
        // 拿起来
        assertEquals(Motion.FIDGET, f.run(4, still.copy(accStd = 1.5, gyroMean = 0.5)).last())
        // 又放下：2 分钟后才回到静止
        val back = f.run(130, still)
        assertTrue(back.firstSecond(Motion.STILL) in 120..124, "${back.firstSecond(Motion.STILL)}")
    }

    @Test
    fun walkingIsSeenWithinSeconds() {
        val out = Feed().run(60, swing)
        assertTrue(out.firstSecond(Motion.WALK) <= 6, "口袋里大幅摆动 3 窗就判走路：${out.firstSecond(Motion.WALK)}")
        assertTrue(out.drop(3).all { it == Motion.WALK })
    }

    @Test
    fun rhythmAloneIsWalking() {
        val out = Feed().run(60, inHandWalk)
        assertEquals(16, out.firstSecond(Motion.WALK), "手里拿着走（摆动小、有节奏）：20 秒窗口里攒够 24 个峰")
        // 同样的晃动没有节奏（旧录制没有 acc_peaks、或车上）：1 分钟后算坐车
        val noRhythm = Feed().run(90, inHandWalk.copy(accPeaks = null))
        assertEquals(-1, noRhythm.firstSecond(Motion.WALK))
        assertEquals(62, noRhythm.firstSecond(Motion.VEHICLE))
        // 节奏峰够了但晃得太轻（< 0.5 m/s²）不算走路
        assertEquals(-1, Feed().run(30, inHandWalk.copy(accStd = 0.3)).firstSecond(Motion.WALK))
    }

    @Test
    fun walkingKeepsThroughShortStops() {
        val f = Feed()
        f.run(30, swing)
        assertTrue(f.run(40, fidget).all { it == Motion.WALK }, "停下等红灯 40 秒还算走路")
        assertEquals(Motion.WALK, f.run(10, swing).last())
        // 停了 1 分钟以上又一直有晃动：上了车
        val bus = f.run(120, ride)
        assertEquals(Motion.WALK, bus.first())
        assertEquals(Motion.VEHICLE, bus.last())
    }

    @Test
    fun steadyShakingWithoutStepsBecomesVehicleAfterAMinute() {
        val out = Feed().run(120, ride)
        assertEquals(62, out.firstSecond(Motion.VEHICLE))
        assertTrue(out.take(29).all { it == Motion.FIDGET })
        // 只晃一会儿（拿着玩手机 40 秒）不算
        val f = Feed()
        f.run(40, fidget)
        f.run(50, still.copy(accStd = 0.02, gyroMean = 0.03))
        assertEquals(-1, f.run(40, fidget).firstSecond(Motion.VEHICLE), "中间安静了 30 秒以上，重新计时")
    }

    @Test
    fun fastGpsIsVehicleAndSwingInTheCarIsNotWalking() {
        val f = Feed()
        assertEquals(Motion.VEHICLE, f.run(2, ride) { it.gps(6.5) }.single(), "卫星速度 > 3 m/s 直接算坐车")
        assertTrue(f.run(20, swing) { it.gps(2.0) }.all { it == Motion.VEHICLE }, "车上拿手机晃（开车一 07:50:36 那样）不算走路")
        // 下车走路：快速移动过去 1 分钟后摆动才算走路
        val out = f.run(80, swing) { it.gps(1.3) }
        assertEquals(Motion.VEHICLE, out.first())
        assertEquals(Motion.WALK, out.last())
    }

    @Test
    fun gpsSayingNotMovingEndsVehicleAndWaitsBeforeTryingAgain() {
        val f = Feed()
        f.run(70, ride)
        assertEquals(Motion.VEHICLE, f.classifier.raw)
        val parked = f.run(200, ride) { it.gps(0.2) }
        assertEquals(182, parked.firstSecond(Motion.FIDGET), "卫星连续 3 分钟说没在走")
        assertEquals(Motion.FIDGET, parked.last())
        // 关了卫星、还在晃：5 分钟内不再判坐车，之后再判一次（以后接上策略时就是再开卫星看一次）
        val later = f.run(400, ride)
        val again = later.firstSecond(Motion.VEHICLE)
        assertTrue(again in 270..290, "过了 5 分钟才再判坐车：$again")
    }

    @Test
    fun slowGpsFromBeforeAGapDoesNotEndARide() {
        // 车停着时卫星开着、说没在走；关了卫星开车 5 分钟；等红灯时开卫星量一下（移动 Wi-Fi 探测）：
        // 第一个点说速度 0，不能把关卫星前那段「没在走」接上、当成连续 3 分钟没在走而结束坐车
        val f = Feed()
        f.run(40, still.copy(accStd = 0.02, gyroMean = 0.03)) { it.gps(0.0) }
        f.run(300, ride)
        assertEquals(Motion.VEHICLE, f.classifier.raw)
        assertEquals(Motion.VEHICLE, f.run(2, still.copy(accStd = 0.02, gyroMean = 0.03)) { it.gps(0.0) }.single())
    }

    @Test
    fun vehicleEndsAfterThreeMinutesWithoutShaking() {
        val f = Feed()
        f.run(70, ride)
        // 坐着不晃但也没完全静止（陀螺 0.05）：3 分钟后不再算坐车
        val out = f.run(200, still.copy(accStd = 0.04, gyroMean = 0.05))
        // 窗口里还带着坐车的晃动时算有动静（到第 8 秒），之后满 180 秒没动静
        assertEquals(188, out.firstSecond(Motion.FIDGET))
    }

    @Test
    fun sameFixedWifiMeansFidget() {
        assertTrue(Feed().run(120, swing) { it.wifi() }.drop(1).all { it == Motion.FIDGET }, "屋里连着 Wi-Fi 走动")
        assertTrue(Feed().run(120, ride) { it.wifi() }.all { it == Motion.FIDGET })
        // 热点、随身 Wi-Fi（计费）当作没连
        assertEquals(Motion.WALK, Feed().run(20, swing) { it.wifi(metered = true) }.last())
        // 换了一个 Wi-Fi（BSSID 变了）的那一下不算「同一个」
        val f = Feed()
        f.run(20, swing) { it.wifi("a") }
        assertEquals(listOf(Motion.WALK, Motion.FIDGET), f.run(4, swing) { it.wifi("b") })
        // 断了 Wi-Fi 出门：马上算走路
        assertEquals(Motion.WALK, f.run(2, swing).single())
        // 卫星说在快速移动时不改（车上的 Wi-Fi）
        assertEquals(Motion.VEHICLE, Feed().run(4, ride) { it.wifi().gps(8.0) }.last())
    }

    @Test
    fun airplaneModeIsOffAndRestartsAfterwards() {
        val f = Feed()
        f.run(130, still)
        assertTrue(f.run(10, still) { it.copy(airplane = true) }.all { it == Motion.OFF })
        val after = f.run(130, still)
        assertEquals(Motion.FIDGET, after.first(), "退出飞行模式从头判")
        assertEquals(122, after.firstSecond(Motion.STILL))
    }

    @Test
    fun gapOrClockJumpRestarts() {
        val f = Feed()
        f.run(40, fidget)
        f.time += 60_000
        assertEquals(Motion.FIDGET, f.run(2, still).single(), "录制断了一分钟")
        f.run(40, swing)
        f.time -= 10_000
        assertEquals(Motion.FIDGET, f.run(2, still).single(), "时间倒退")
    }

    @Test
    fun stillSurvivesAGapUntilSomethingMoves() {
        // 静止时引擎关了加速度、只等显著运动叫醒：断开不算重来，还是静止，有动静才出来
        val f = Feed()
        f.run(130, still)
        f.time += 600_000
        assertEquals(listOf(Motion.STILL, Motion.STILL), f.run(4, still))
        f.time += 600_000
        assertEquals(Motion.FIDGET, f.run(4, fidget).last())
    }

    @Test
    fun fixedWifiKeepsIndoorsWhileMovingButRemembersTheRawJudgement() {
        // 连着锚 Wi-Fi 一直在走也一直按屋里算：不再有「10 分钟后不信 Wi-Fi」的规则，Wi-Fi 跟没跟着人走由 policy/AnchorProbe 开卫星量
        val walking = Feed().run(700, swing) { it.wifi() }
        assertTrue(walking.drop(1).all { it == Motion.FIDGET }, "走了 ${walking.firstSecond(Motion.WALK)} 秒按走路")
        // 车载 Wi-Fi：开车 90 秒、等红灯 40 秒。给出去的是屋里，自己记的坐车不因为改判清掉，红灯里还是坐车
        val f = Feed()
        assertTrue(f.run(90, ride) { it.wifi() }.all { it == Motion.FIDGET })
        assertEquals(Motion.VEHICLE, f.classifier.raw)
        assertTrue(f.run(40, still.copy(accStd = 0.02, gyroMean = 0.03)) { it.wifi() }.all { it == Motion.FIDGET })
        assertEquals(Motion.VEHICLE, f.classifier.raw, "等红灯还是坐车")
        // 不再改判（量出 Wi-Fi 跟着人走、锚清掉）：马上接着坐车，不用再晃满 1 分钟
        assertEquals(Motion.VEHICLE, f.run(2, ride).single())
    }
}
