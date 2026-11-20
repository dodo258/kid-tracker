package org.traccar.client.sense

import org.traccar.client.motion.MotionClassifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowStatsTest {

    private val g = WindowStats.GRAVITY

    /** 25 次/秒喂 [seconds] 秒的加速度（z 轴 = 重力 + [signal]），每 2 秒收一窗，返回每窗的汇总 */
    private fun windows(seconds: Int, signal: (Double) -> Double): List<WindowStats.Summary> {
        val stats = WindowStats()
        val out = mutableListOf<WindowStats.Summary>()
        for (i in 0 until seconds * 25) {
            val t = i * 40L
            stats.onAccelerometer(t, 0f, 0f, (g + signal(t / 1000.0)).toFloat())
            if ((i + 1) % 50 == 0) out += stats.close()
        }
        return out
    }

    /** 走路样的加速度：基频 [hz] 加二次谐波和噪声（口袋里每步一个大峰） */
    private fun walk(hz: Double, amplitude: Double, noise: Double, random: Random): (Double) -> Double = { t ->
        amplitude * sin(2 * PI * hz * t) + 0.3 * amplitude * sin(4 * PI * hz * t + 0.5) + noise * random.nextGaussian()
    }

    private fun Random.nextGaussian(): Double {
        // Box–Muller；nextDouble() 可能是 0，避开 log(0)
        val u = 1.0 - nextDouble()
        val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
    }

    /** 运动判断看的 20 秒（10 窗）里合节奏的峰：走路取最少的一段（跳过开头 10 秒，基线、幅度还在收敛），不是走路取最多的一段 */
    private fun minPeaks(w: List<WindowStats.Summary>) = w.drop(5).windowed(10).minOf { c -> c.sumOf { it.accPeaks } }
    private fun maxPeaks(w: List<WindowStats.Summary>) = w.windowed(10).maxOf { c -> c.sumOf { it.accPeaks } }
    private val walkingNeeds = MotionClassifier.RHYTHM_PEAKS

    @Test
    fun summaryMatchesTheRecorderFormula() {
        val stats = WindowStats()
        listOf(1.0, -1.0, 3.0).forEachIndexed { i, a -> stats.onAccelerometer(i * 40L, 0f, 0f, (g + a).toFloat()) }
        stats.onGyroscope(0f, 3f, 4f)
        stats.onGyroscope(0f, 0f, 1f)
        stats.onAzimuth(350 * PI / 180)
        stats.onAzimuth(10 * PI / 180)
        val s = stats.close()
        assertEquals(1.0, s.accMean!!, 1e-5)
        assertEquals(1.633, s.accStd!!, 1e-3)
        assertEquals(3.0, s.accMax!!, 1e-5)
        assertEquals(3, s.accN)
        assertEquals(3.0, s.gyroMean!!, 1e-6)
        assertEquals(5.0, s.gyroMax!!, 1e-6)
        assertTrue(abs(s.heading!!) < 1e-6 || abs(s.heading!! - 360) < 1e-6, "350° 和 10° 平均是 0°，不是 180°：${s.heading}")
        assertEquals(2, s.headingN)

        val empty = stats.close()
        assertEquals(0, empty.accN)
        assertNull(empty.accStd)
        assertNull(empty.gyroMean)
        assertNull(empty.heading)
    }

    @Test
    fun magneticFluctuationIsTheSpreadOfFieldStrength() {
        val stats = WindowStats()
        // 转着手机（方向变）强度不变：波动是 0
        stats.onMagnetic(30f, 40f, 0f)
        stats.onMagnetic(0f, 0f, 50f)
        stats.onMagnetic(-40f, 0f, 30f)
        assertEquals(0.0, stats.close().magStd!!, 1e-4)
        // 强度 40、50、60 μT：标准差 8.165
        stats.onMagnetic(40f, 0f, 0f)
        stats.onMagnetic(0f, 50f, 0f)
        stats.onMagnetic(0f, 0f, 60f)
        assertEquals(8.165, stats.close().magStd!!, 1e-3)
        stats.onMagnetic(40f, 0f, 0f)
        assertNull(stats.close().magStd, "一个读数算不出波动")
        assertNull(stats.close().magStd, "没开磁力计")
    }

    @Test
    fun walkingHasRhythm() {
        val random = Random(7)
        for ((name, signal) in listOf(
            "口袋里正常走路 1.8 Hz" to walk(1.8, 2.5, 0.3, random),
            "拿在手里慢走 1.5 Hz" to walk(1.5, 0.8, 0.2, random),
            "小孩快走 2.4 Hz" to walk(2.4, 3.0, 0.5, random),
        )) {
            val peaks = minPeaks(windows(60, signal))
            println("$name：20 秒最少 $peaks 个合节奏的峰（判走路要 $walkingNeeds）")
            assertTrue(peaks >= walkingNeeds + 4, "$name：20 秒最少 $peaks 个峰")
        }
    }

    @Test
    fun stillAndVehicleHaveNoRhythm() {
        val random = Random(11)
        assertEquals(0, maxPeaks(windows(60) { 0.01 * random.nextGaussian() }), "桌上")
        // 车上：4–15 Hz 的震动叠加 0.3 Hz 的慢晃和噪声
        val parts = List(12) { Triple(4 + 11 * random.nextDouble(), 2 * PI * random.nextDouble(), 0.3 + 0.7 * random.nextDouble()) }
        val vehicle = windows(120) { t -> parts.sumOf { (f, p, a) -> a * sin(2 * PI * f * t + p) } / 2 + 0.5 * sin(2 * PI * 0.3 * t) + 0.5 * random.nextGaussian() }
        // 1.3 Hz 的车身起伏，忽大忽小，叠加噪声
        val bounce = windows(120) { t -> 0.8 * sin(2 * PI * 1.3 * t) * (0.5 + 0.5 * sin(2 * PI * 0.1 * t)) + 0.6 * random.nextGaussian() }
        println("车上的震动：20 秒最多 ${maxPeaks(vehicle)} 个合节奏的峰；车身起伏：最多 ${maxPeaks(bounce)} 个（判走路要 $walkingNeeds）")
        assertTrue(maxPeaks(vehicle) <= walkingNeeds - 10, "车上的震动：20 秒最多 ${maxPeaks(vehicle)} 个峰")
        assertTrue(maxPeaks(bounce) <= walkingNeeds - 4, "车身起伏（频率在走路范围里，但忽大忽小）：20 秒最多 ${maxPeaks(bounce)} 个峰")
    }

    @Test
    fun rhythmRestartsAfterASensorGap() {
        val rhythm = GaitRhythm()
        val random = Random(3)
        val signal = walk(1.8, 2.5, 0.3, random)
        var peaks = 0
        for (i in 0 until 250) if (rhythm.onSample(i * 40L, signal(i * 0.04))) peaks++
        assertTrue(peaks > 0)
        // 停了 2 秒再来：第一下只是重新开始，不会把隔着空档的两个峰算成合节奏
        assertEquals(false, rhythm.onSample(250 * 40L + 2_000, 5.0))
        assertEquals(false, rhythm.onSample(250 * 40L + 2_040, 5.0))
    }
}
