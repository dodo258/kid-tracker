package org.traccar.client.sense

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * 从加速度大小里数走路节奏的峰（方案第 3 节：走路靠 1.5–2.5 Hz 的节奏，不靠计步器——华为的计步器攒着一批一批报）。
 * 连续喂样本（约 25 次/秒），跨窗口保持状态：
 * - 先轻微平滑（时间常数 [SMOOTH_SECONDS]，去掉高频抖动），再减去慢基线（[BASELINE_SECONDS]，跟着姿势和零点偏差走）；
 * - 偏离基线超过 max(平均偏离的一半, [MIN_RISE]) 算一个峰，回到基线以下才再算下一个（一步一个）；
 * - 和上一个峰隔 [MIN_INTERVAL_MILLIS]–[MAX_INTERVAL_MILLIS] 毫秒（1.25–3 Hz），且和上一个间隔相差不到 [REGULARITY]，
 *   才算「合节奏」。车上的颠簸、随手晃动间隔乱，很少连着合节奏。
 * 样本断开超过 [MAX_GAP_SECONDS] 秒（传感器停了）就从头来。
 */
class GaitRhythm {
    private var lastTime = 0L
    private var started = false
    private var smooth = 0.0
    private var baseline = 0.0
    private var spread = 0.0
    private var armed = false
    private var lastPeak: Long? = null
    private var lastInterval: Long? = null

    /** 喂一个样本（去掉重力后的加速度大小，m/s²）；这个样本是一个合节奏的峰时返回 true */
    fun onSample(timeMillis: Long, magnitude: Double): Boolean {
        val dt = (timeMillis - lastTime) / 1000.0
        if (!started || dt <= 0 || dt > MAX_GAP_SECONDS) {
            reset(timeMillis, magnitude)
            return false
        }
        lastTime = timeMillis
        smooth += (1 - exp(-dt / SMOOTH_SECONDS)) * (magnitude - smooth)
        baseline += (1 - exp(-dt / BASELINE_SECONDS)) * (smooth - baseline)
        val deviation = smooth - baseline
        spread += (1 - exp(-dt / SPREAD_SECONDS)) * (abs(deviation) - spread)
        if (deviation < 0) {
            armed = true
            return false
        }
        if (!armed || deviation <= max(spread / 2, MIN_RISE)) return false
        armed = false
        val previous = lastPeak
        lastPeak = timeMillis
        if (previous == null) return false
        val interval = timeMillis - previous
        if (interval !in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
            lastInterval = null
            return false
        }
        val before = lastInterval
        lastInterval = interval
        return before != null && abs(interval - before) <= REGULARITY * before
    }

    private fun reset(timeMillis: Long, magnitude: Double) {
        started = true
        lastTime = timeMillis
        smooth = magnitude
        baseline = magnitude
        spread = 0.0
        armed = false
        lastPeak = null
        lastInterval = null
    }

    companion object {
        const val SMOOTH_SECONDS = 0.08
        const val BASELINE_SECONDS = 1.5
        const val SPREAD_SECONDS = 2.0

        /** m/s²：比这小的起伏不算一步（桌上的噪声约 0.01） */
        const val MIN_RISE = 0.25
        const val MIN_INTERVAL_MILLIS = 330L
        const val MAX_INTERVAL_MILLIS = 800L
        const val REGULARITY = 0.35
        const val MAX_GAP_SECONDS = 0.5
    }
}
