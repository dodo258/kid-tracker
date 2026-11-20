package org.traccar.client.motion

import org.traccar.client.sense.Sky
import org.traccar.client.sense.Snapshot
import kotlin.math.sqrt

/**
 * 运动判断（方案 docs/specs/2026-11-18-reporter-sensor-first.md 第 3 节 motion/）：按时间顺序喂 2 秒一份的快照，
 * 每份给出当前 [Motion]。只看快照，不碰 Android、不开定位；阈值按 P20 两天录制定（见方案末尾「第一段实现记录」）。
 *
 * - 有动静：最近 [WINDOW_MILLIS] 内的加速度波动（几窗合起来的标准差）≥ [ACTIVE_ACC]。
 * - [Motion.STILL]：连续 [STILL_MILLIS] 每窗加速度波动 < [QUIET_ACC] 且陀螺 < [QUIET_GYRO]（卫星说在快速移动的窗不算）；进了 STILL，没到「有动静」
 *   就一直是 STILL（夜里偶尔一下 0.08 的轻震不算醒），卫星说在快速移动除外。
 * - [Motion.WALK]：最近 [RHYTHM_WINDOW_MILLIS] 里有走路节奏（合节奏的峰 ≥ [RHYTHM_PEAKS] 且最近 [WINDOW_MILLIS] 波动 ≥ [RHYTHM_ACC]），
 *   或大幅摆动（≥ [SWING_COUNT] 窗加速度波动 ≥ [SWING_ACC] 且陀螺 ≥ [SWING_GYRO]，口袋里走路腿带着手机转）。
 *   不看计步器（华为攒着报，录制里有躺着不动时一下报 62 步、走完 1.5 小时才报 887 步的）。
 *   走路停下 [GAIT_HOLD_MILLIS] 内还算走路（等红灯）；卫星速度 [FAST_HOLD_MILLIS] 内超过 [VEHICLE_SPEED] 时摆动不算走路（车上拿手机）。
 * - 卫星速度只认开阔天空下的点（精度 ≤ 50 m，[Sky.OPEN]）。
 * - [Motion.VEHICLE]：卫星速度 > [VEHICLE_SPEED]；或有动静持续 [VEHICLE_AFTER_MILLIS]、这期间没走路节奏（中间安静不到
 *   [BOUT_GAP_MILLIS] 算连着）。进了就保持，直到静止、走路、[VEHICLE_IDLE_MILLIS] 没动静，或卫星连续 [DENY_MILLIS]
 *   说没在走（速度 ≤ [SLOW_SPEED]）——那时转 FIDGET，[RECHECK_MILLIS] 内不再凭晃动判坐车（以后接上定位策略时，就是隔这么久再开卫星看一次）。
 * - 还连着上次卫星定位时的 Wi-Fi（调用方告诉它，见 policy/WifiBook）时，走路、坐车都改判 [Motion.FIDGET]；卫星说在快速移动时
 *   除外（车上的 Wi-Fi）。只改给出去的结果，自己记的还是没改判的（[raw]）：等红灯、停一下不会把坐车、走路的记忆清掉，
 *   探测量出 Wi-Fi 跟着人走（policy/AnchorProbe）、不再改判时马上接着原来的判断。
 * - [Motion.OFF]：飞行模式。快照断开超过 [MAX_GAP_MILLIS]、时间倒退、刚退出飞行模式，都从头判（先当 FIDGET）；
 *   静止中断开（静止时引擎关了加速度，只等硬件唤醒）不算重来，还是静止，等「有动静」再出来。
 */
internal class MotionClassifier {

    /** 没被 Wi-Fi 改判的判断（[update] 返回的是改判后的） */
    var raw = Motion.FIDGET
        private set

    private val window = ArrayDeque<Snapshot>()
    private var lastTime: Long? = null
    private var quietSince: Long? = null
    private var boutStart: Long? = null
    private var lastActive: Long? = null
    private var lastGait: Long? = null
    private var lastFast: Long? = null
    private var lastGps: Long? = null
    private var slowSince: Long? = null
    private var vehicleBlockedUntil: Long? = null
    private var lastFixedWifi: String? = null
    private var seeded = false

    /** 重启前是静止：接着静止，和静止中断开一样，等「有动静」再出来 */
    fun seedStill() {
        raw = Motion.STILL
        seeded = true
    }

    /**
     * 喂一份快照。[fixedWifi] 是这份快照连着的、上次卫星定位时也连着的 Wi-Fi 的 BSSID，没连或不是那个是 null。
     */
    fun update(s: Snapshot, fixedWifi: String? = null): Motion {
        // 快照间隔按开机时长算（[Snapshot.clock]）：系统时间被改快一天也接着判，不当成断了一天从头来（审计 11.0.11 第 6 条）
        val t = s.clock
        val previous = lastTime
        lastTime = t
        if (s.airplane == true) {
            reset()
            raw = Motion.OFF
            return raw
        }
        if (previous == null || t - previous !in 1..MAX_GAP_MILLIS || raw == Motion.OFF) {
            val stayStill = (previous != null || seeded) && raw == Motion.STILL
            reset()
            if (stayStill) raw = Motion.STILL
        }
        seeded = false

        window.addLast(s)
        while (window.first().clock <= t - RHYTHM_WINDOW_MILLIS) window.removeFirst()
        val recent = window.filter { it.clock > t - WINDOW_MILLIS }
        val spread = pooledStd(recent)
        val active = spread >= ACTIVE_ACC
        // 只拿开阔天空下的卫星点测速（[Sky]）：屋里几百米、几公里误差的点带的速度不可信
        val speed = s.freshGps(GPS_FRESH_MILLIS)?.takeIf { Sky.of(it.accuracy) == Sky.OPEN }?.speed
        val fast = speed != null && speed > VEHICLE_SPEED
        // 卫星正说在快速移动时不算安静（手机稳放在平顺的车上）
        val quiet = s.accStd != null && s.accStd < QUIET_ACC && (s.gyroMean ?: 0.0) < QUIET_GYRO && !fast
        quietSince = if (quiet) quietSince ?: t else null
        if (active) {
            val last = lastActive
            if (boutStart == null || last == null || t - last >= BOUT_GAP_MILLIS) boutStart = t
            lastActive = t
        }

        if (speed != null) {
            // 卫星断过一阵（关着、隧道里）再来：以前那段「没在走」不接着算，不然停着时开卫星量一下，第一个点就当成连续 3 分钟没在走
            if (!within(lastGps, t, GPS_STALE_MILLIS)) slowSince = null
            lastGps = t
            if (fast) lastFast = t
            slowSince = if (speed > SLOW_SPEED) null else slowSince ?: t
        }
        val gpsDenies = lasted(slowSince, t, DENY_MILLIS) && within(lastGps, t, GPS_STALE_MILLIS)
        val gait = !within(lastFast, t, FAST_HOLD_MILLIS) && (swinging(recent) || rhythmic(spread))
        if (gait) lastGait = t
        val gaitRecent = within(lastGait, t, GAIT_HOLD_MILLIS)
        val vehicleBlocked = vehicleBlockedUntil.let { it != null && t < it }

        val sameFixedWifi = fixedWifi != null && fixedWifi == lastFixedWifi
        lastFixedWifi = fixedWifi

        // 开阔天空下的高速卫星点排在「此前静止、没动静」前面：平稳的高铁、轿车上从静止出来（审计 11.0.11 第 7 条）
        val next = when {
            lasted(quietSince, t, STILL_MILLIS) -> Motion.STILL
            fast -> Motion.VEHICLE
            raw == Motion.STILL && !active -> Motion.STILL
            gait -> Motion.WALK
            raw == Motion.WALK && gaitRecent -> Motion.WALK
            raw == Motion.VEHICLE && !gpsDenies && within(lastActive, t, VEHICLE_IDLE_MILLIS) -> Motion.VEHICLE
            active && lasted(boutStart, t, VEHICLE_AFTER_MILLIS) && !gaitRecent && !gpsDenies && !vehicleBlocked -> Motion.VEHICLE
            else -> Motion.FIDGET
        }
        if (raw == Motion.VEHICLE && next == Motion.FIDGET && gpsDenies) vehicleBlockedUntil = t + RECHECK_MILLIS
        raw = next
        return if (next.moving && sameFixedWifi && !fast) Motion.FIDGET else next
    }

    /** 从 [from] 起到 [t] 已经满 [millis] */
    private fun lasted(from: Long?, t: Long, millis: Long) = from != null && t - from >= millis

    /** [from] 在 [t] 前 [millis] 以内 */
    private fun within(from: Long?, t: Long, millis: Long) = from != null && t - from < millis

    /** 几份快照合起来的加速度标准差（姿势变了、均值挪了也算进去） */
    private fun pooledStd(snapshots: List<Snapshot>): Double {
        var n = 0
        var sum = 0.0
        var squares = 0.0
        for (w in snapshots) {
            val mean = w.accMean ?: continue
            val std = w.accStd ?: continue
            if (w.accN < 2) continue
            n += w.accN
            sum += w.accN * mean
            squares += w.accN * (std * std + mean * mean)
        }
        if (n == 0) return 0.0
        val m = sum / n
        return sqrt((squares / n - m * m).coerceAtLeast(0.0))
    }

    private fun swinging(snapshots: List<Snapshot>): Boolean =
        snapshots.count { (it.accStd ?: 0.0) >= SWING_ACC && (it.gyroMean ?: 0.0) >= SWING_GYRO } >= SWING_COUNT

    /** 旧录制、模拟器没有 acc_peaks 列时不算 */
    private fun rhythmic(spread: Double): Boolean =
        spread >= RHYTHM_ACC && window.sumOf { it.accPeaks ?: 0 } >= RHYTHM_PEAKS

    private fun reset() {
        raw = Motion.FIDGET
        window.clear()
        quietSince = null
        boutStart = null
        lastActive = null
        lastGait = null
        lastFast = null
        lastGps = null
        slowSince = null
        vehicleBlockedUntil = null
        lastFixedWifi = null
    }

    companion object {
        const val WINDOW_MILLIS = 10_000L
        const val MAX_GAP_MILLIS = 10_000L

        /** m/s²：有动静。P20 夜里静止每窗最高 0.081，有动静的分钟中位数最低 0.13 */
        const val ACTIVE_ACC = 0.15

        /** 静止：每窗加速度波动 < 0.05 m/s² 且陀螺 < 0.01 rad/s，连续 2 分钟（P20 夜里 0.007 / 0.001） */
        const val QUIET_ACC = 0.05
        const val QUIET_GYRO = 0.01
        const val STILL_MILLIS = 120_000L

        /** 大幅摆动：P20 口袋里走路每窗 3–9 m/s²、陀螺 1.5–3；开车时陀螺 0.15–0.6 */
        const val SWING_ACC = 2.0
        const val SWING_GYRO = 0.8
        const val SWING_COUNT = 3

        /**
         * 走路节奏：20 秒里合节奏的峰 ≥ 24 个（1.2 步/秒以上），波动 ≥ 0.5 m/s²。合成信号上：1.5–2.4 Hz 走路 28 个以上，
         * 1.3 Hz 忽大忽小的车身起伏最多 17 个，车上的震动最多 7 个（见 WindowStatsTest）
         */
        const val RHYTHM_WINDOW_MILLIS = 20_000L
        const val RHYTHM_PEAKS = 24
        const val RHYTHM_ACC = 0.5

        const val GAIT_HOLD_MILLIS = 60_000L
        const val VEHICLE_AFTER_MILLIS = 60_000L
        const val BOUT_GAP_MILLIS = 30_000L
        const val VEHICLE_IDLE_MILLIS = 180_000L

        /** m/s：卫星速度超过它算坐车（方案第 3 节） */
        const val VEHICLE_SPEED = 3.0

        /** m/s：卫星速度不超过它算没在走 */
        const val SLOW_SPEED = 1.0
        const val GPS_FRESH_MILLIS = 10_000L
        const val GPS_STALE_MILLIS = 30_000L
        const val FAST_HOLD_MILLIS = 60_000L
        const val DENY_MILLIS = 180_000L
        const val RECHECK_MILLIS = 300_000L
    }
}
