package org.traccar.client.sense

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一窗（2 秒）里加速度、陀螺、朝向、磁场的累计。Android 侧把传感器事件原样喂进来，到点 [close] 取汇总并清零；
 * 走路节奏（[GaitRhythm]）跨窗口连续。算法和 2026-11-17 起的录制一样（P20 的阈值按它定）：
 * 加速度取三轴合成大小减重力，波动是这一窗的标准差。
 */
class WindowStats {
    private val rhythm = GaitRhythm()
    private var accSum = 0.0
    private var accSqSum = 0.0
    private var accMax = 0.0
    private var accN = 0
    private var peaks = 0
    private var gyroSum = 0.0
    private var gyroMax = 0.0
    private var gyroN = 0
    private var headingSin = 0.0
    private var headingCos = 0.0
    private var headingN = 0
    private var magSum = 0.0
    private var magSqSum = 0.0
    private var magN = 0

    fun onAccelerometer(timeMillis: Long, x: Float, y: Float, z: Float) {
        val m = sqrt((x * x + y * y + z * z).toDouble()) - GRAVITY
        accSum += m
        accSqSum += m * m
        if (abs(m) > accMax) accMax = abs(m)
        accN++
        if (rhythm.onSample(timeMillis, m)) peaks++
    }

    fun onGyroscope(x: Float, y: Float, z: Float) {
        val m = sqrt((x * x + y * y + z * z).toDouble())
        gyroSum += m
        if (m > gyroMax) gyroMax = m
        gyroN++
    }

    /** 方位角（弧度，SensorManager.getOrientation 的第一个值）；按单位向量求平均，跨 0° 不出错 */
    fun onAzimuth(radians: Double) {
        headingSin += sin(radians)
        headingCos += cos(radians)
        headingN++
    }

    /** 磁场三轴（μT）；只记合成大小，朝向变了大小不变，波动只来自周围的铁和电器 */
    fun onMagnetic(x: Float, y: Float, z: Float) {
        val m = sqrt((x * x + y * y + z * z).toDouble())
        magSum += m
        magSqSum += m * m
        magN++
    }

    /** 取这一窗的汇总并清零 */
    fun close(): Summary {
        val mean = if (accN > 0) accSum / accN else null
        val std = if (accN > 1 && mean != null) sqrt((accSqSum / accN - mean * mean).coerceAtLeast(0.0)) else null
        val magMean = if (magN > 1) magSum / magN else null
        val heading = if (headingN > 0) (atan2(headingSin, headingCos) * 180 / PI).let { if (it < 0) it + 360 else it } else null
        val summary = Summary(
            accMean = mean,
            accStd = std,
            accMax = if (accN > 0) accMax else null,
            accN = accN,
            accPeaks = peaks,
            gyroMean = if (gyroN > 0) gyroSum / gyroN else null,
            gyroMax = if (gyroN > 0) gyroMax else null,
            heading = heading,
            headingN = headingN,
            magStd = magMean?.let { sqrt((magSqSum / magN - it * it).coerceAtLeast(0.0)) },
        )
        accSum = 0.0; accSqSum = 0.0; accMax = 0.0; accN = 0; peaks = 0
        gyroSum = 0.0; gyroMax = 0.0; gyroN = 0
        headingSin = 0.0; headingCos = 0.0; headingN = 0
        magSum = 0.0; magSqSum = 0.0; magN = 0
        return summary
    }

    data class Summary(
        val accMean: Double?,
        val accStd: Double?,
        val accMax: Double?,
        val accN: Int,
        val accPeaks: Int,
        val gyroMean: Double?,
        val gyroMax: Double?,
        val heading: Double?,
        val headingN: Int,
        /** 磁场强度的标准差（μT）；这一窗不到两个读数是 null */
        val magStd: Double? = null,
    )

    companion object {
        /** m/s²，和 Android SensorManager.GRAVITY_EARTH 一样 */
        const val GRAVITY = 9.80665
    }
}
