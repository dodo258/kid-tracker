package org.traccar.client.policy

import org.traccar.client.motion.Motion

/**
 * 连着锚 Wi-Fi 时什么时候开卫星「量一量」离锚点多远（方案 docs/specs/2026-11-19-moving-wifi.md 4.2–4.3）。只管时机和冷却，
 * 量的结果由 [Judge] 拿卫星点和 [WifiBook.beyond] 比出来再告诉它（[finish]、[walked]）。纯逻辑，时间都是毫秒。
 *
 * - 开：连着锚 Wi-Fi、没在路上，没被改判的运动判断最近 [WINDOW] 里累计走路 / 坐车满 [NEED]（跟着人走过的 Wi-Fi 满 [NEED_HINTED]）；
 *   按累计算，等红灯、停一下不清零。网络估算离锚点太远、出现锚这里没见过的基站（离开信号）时不看运动判断、不看冷却，
 *   只要离这个锚上次量完过了 [RETRY] 就马上开（[start] 的 force）。
 * - 每次最多 [LIMIT]，没定到（或量出来说不清，见 [WifiBook.near]）就 [RETRY] 后满足条件再试。一边走一边量（[walked]）时
 *   不凭一个近点下结论：从第一个点起再量满 [LIMIT]（整次最多 [WALK_LIMIT]），期间离第一个点超过 [WifiBook.HERE_METERS] 加精度就是在离开。
 * - 量出来还在锚这里：冷却，同一个 Wi-Fi 下接连几次都在，按 [COOLDOWN] → 2 倍 → 3 倍拉长（最多 [MAX_COOLDOWN_STEPS] 倍）。
 *   冷却和重试绑在「这次连着的这个锚 + 这个锚点」上：断开或换了 Wi-Fi（同一个 Wi-Fi 断开重连也算）、锚被清、锚点挪了地方都清掉（[forget]）。
 *   量出来离开了：不冷却。刚在家量过，接着上车连上车载 Wi-Fi（新锚），照样马上能量。
 */
internal class AnchorProbe {

    /** 这次探测从什么时候开始，没在探测是 null */
    private var since: Long? = null
    private var target: String? = null
    private var blockedUntil = Long.MIN_VALUE

    /** 离开信号（[start] 的 force）最早什么时候能再量：这个锚上次量完 [RETRY] 后 */
    private var retryAt = Long.MIN_VALUE

    /** 冷却、重试挡的是哪个锚 */
    private var blockedFor: String? = null

    /** 一边走一边量时这次探测的第一个点、收到它的时刻、最近一个点明显在锚这里没有 */
    private var first: GpsPoint? = null
    private var firstAt = 0L
    private var lastNear = false
    private var streak = 0
    private var streakWifi: String? = null
    private var streakLink: Int? = null
    private val moving = ArrayDeque<Long>()

    val active: Boolean get() = since != null

    /**
     * 这次探测拿来比的锚点：开始量时的那个，量的过程中不跟着挪（审计 11.0.11 第 2 条：被动定位先把远处的点送进快照、锚点先挪了过去，
     * 定位回调再拿挪过的锚点比，就成了「还在这里」）
     */
    var origin: GpsPoint? = null
        private set

    /**
     * 每份快照：[wifi] 是连着的 Wi-Fi（没连是 null），[link] 是这条连接的代次（[org.traccar.client.sense.Snapshot.netLink]），
     * [anchor] 是连着的锚 Wi-Fi（没连锚、已经在路上是 null），[at] 是它的锚点，[raw] 是没被改判的运动判断，
     * [hinted] 是这个锚跟着人走过（或随机 MAC）。该开卫星量了返回 true。
     */
    fun onSnapshot(now: Long, wifi: String?, link: Int?, anchor: String?, at: GpsPoint?, raw: Motion, hinted: Boolean): Boolean {
        // 换了 Wi-Fi，或者同一个 Wi-Fi 断开又连上（两份快照之间断的，BSSID 一样、代次变了）：以前的冷却不算了（审计 11.0.11 第 3 条）
        if (wifi != streakWifi || link != streakLink) {
            streakWifi = wifi
            streakLink = link
            forget()
        }
        if (anchor == null) {
            moving.clear()
            return false
        }
        if (raw.moving) moving.addLast(now)
        while (moving.isNotEmpty() && moving.first() <= now - WINDOW) moving.removeFirst()
        val need = if (hinted) NEED_HINTED else NEED
        return moving.size * SNAPSHOT_MILLIS >= need && start(now, anchor, at)
    }

    /**
     * 马上量一次，拿 [at]（锚现在的锚点）当这次比的地方（[origin]）；已经在量时不开。[force]（网络估算太远、陌生基站这类离开信号）
     * 不看冷却，只要离这个锚上次量完过了 [RETRY]；不 force（像在动）时这个锚在冷却中不开。开了返回 true
     */
    fun start(now: Long, anchor: String, at: GpsPoint?, force: Boolean = false): Boolean {
        if (since != null) return false
        if (anchor == blockedFor && now < if (force) retryAt else blockedUntil) return false
        since = now
        target = anchor
        origin = at
        moving.clear()
        return true
    }

    /**
     * 到时限了：一边走一边量、最后一个点明显在锚这里，算量完了还在这里（冷却）；别的（没定到、说不清）不下结论，[RETRY] 后再试。
     * 时限一般是 [LIMIT]，一边走一边量时从第一个点起再满 [LIMIT]（整次最多 [WALK_LIMIT]）。
     * 这次结束了返回 true（时间往回跳了也当结束，不让卫星一直开着）
     */
    fun expire(now: Long): Boolean {
        val start = since ?: return false
        val limit = if (first == null) LIMIT else minOf(WALK_LIMIT, firstAt - start + LIMIT)
        if (now - start in 0 until limit) return false
        if (first != null && lastNear) {
            finish(now, here = true)
            return true
        }
        blockedFor = target
        stop()
        blockedUntil = now + RETRY
        retryAt = blockedUntil
        return true
    }

    /**
     * 一边走一边量到一个点（没被改判的运动判断还是走路 / 坐车）：一个近点不说明还在这里（带着随身 Wi-Fi 刚出门），记下这次的第一个点，
     * 离它超过 [WifiBook.HERE_METERS] 加两个点的精度就是在离开，返回 true。[near] 是这个点明显在锚这里
     */
    fun walked(now: Long, point: GpsPoint, near: Boolean): Boolean {
        lastNear = near
        val f = first ?: run {
            first = point
            firstAt = now
            return false
        }
        return distanceMeters(f.latitude, f.longitude, point.latitude, point.longitude) - f.accuracy - point.accuracy >
            WifiBook.HERE_METERS
    }

    /** 量出来了：[here] 还在锚这里就冷却；不在了（锚已清掉）不冷却，以前的冷却也不留 */
    fun finish(now: Long, here: Boolean) {
        val measured = target
        stop()
        if (!here) {
            forget()
            return
        }
        blockedFor = measured
        streak = (streak + 1).coerceAtMost(MAX_COOLDOWN_STEPS)
        blockedUntil = now + COOLDOWN * streak
        retryAt = now + RETRY
    }

    /** 不量了：已经在路上（卫星本来就开着）、锚换了、断开、飞行模式 */
    fun stop() {
        since = null
        target = null
        origin = null
        first = null
        lastNear = false
    }

    /** 冷却、重试都不算了：断开或换了 Wi-Fi、锚被清、锚点挪了地方（冷却只对「这次连着的这个锚 + 这个锚点」有效） */
    fun forget() {
        blockedFor = null
        blockedUntil = Long.MIN_VALUE
        retryAt = Long.MIN_VALUE
        streak = 0
    }

    /** 系统时间被改了 [delta] 毫秒：记的时间跟着挪（和 ReportPolicy.shift 一样），往回改一天不会一天不量、也不会一直开着卫星 */
    fun shift(delta: Long) {
        since = since?.plus(delta)
        firstAt += delta
        if (blockedUntil != Long.MIN_VALUE) blockedUntil += delta
        if (retryAt != Long.MIN_VALUE) retryAt += delta
        for (i in moving.indices) moving[i] += delta
    }

    /** 正在给哪个锚量 */
    val anchor: String? get() = target

    companion object {
        const val SNAPSHOT_MILLIS = 2_000
        const val WINDOW = 180_000L
        const val NEED = 60_000
        const val NEED_HINTED = 20_000
        const val LIMIT = 90_000L
        const val WALK_LIMIT = 180_000L
        const val RETRY = 300_000L
        const val COOLDOWN = 600_000L
        const val MAX_COOLDOWN_STEPS = 3

        /** 网络估算离锚点超过它（加上估算的精度）就量一次 */
        const val NET_FAR_METERS = 1_000.0
    }
}
