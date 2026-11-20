package org.traccar.client.policy

import kotlinx.serialization.Serializable
import org.traccar.client.Position
import org.traccar.client.Provider

/**
 * 上一个报给服务器的主坐标（lat / lon）：[gps] 是卫星点，不是就是 [NetFallback] 顶上的（网络估算或卫星锚点）；[at] 是报出的时刻。
 * 存在状态库里，重启接着比
 */
@Serializable
data class MainFix(val latitude: Double, val longitude: Double, val accuracy: Double, val gps: Boolean, val at: Long)

/**
 * 屋里定不到卫星时拿别的位置顶上主坐标（11-19 实测：P20 屋里卫星点都被挡档丢掉，主坐标停在 12 小时前 2.8 公里外；三星全新装从没有过主坐标，
 * 服务器记成 0,0）。纯逻辑，[Judge] 喂能用的网络估算（热点、跟着人走过的 Wi-Fi 下的 Wi-Fi 定位已经挡掉）、连着的 Wi-Fi 的卫星锚点和报出去的点，
 * 时间都是毫秒。能不能顶、顶什么都在这里判断，调用方只告诉它现在连着哪个卫星锚点。
 *
 * - 可信：有坐标、误差 ≤ [TRUSTED_ACCURACY]（[trusted]）。
 * - 顶什么：
 *   - 连着有卫星锚点的 Wi-Fi（[estimate] 的 anchor）：估算和锚点对得上（离锚点不超过 [WifiBook.HERE_METERS] 加两边的精度）就顶锚点本身
 *     （来源 [Provider.ANCHOR]、精度是锚点的），一个估算就够，不等第二次；对不上多半是位置库记错了（P20 11-18 17:29），卫星锚点优先，
 *     不顶也不催取。
 *   - 别的：连续两个可信估算彼此在误差内（距离不超过两者误差里大的那个）、隔了至少 [CONFIRM_GAP]，顶估算（来源 network、精度是估算误差）。
 * - 什么时候顶：已经 [NO_GPS] 没报过卫星点（或从没有过主坐标），要顶的位置离上一个主坐标超过 [FAR_METERS] + 它的精度（或没有主坐标）；
 *   记下等着下一条不带卫星点的上报带出去（[takePending]）。报了卫星点就作废：卫星点永远优先。
 * - 催取：要顶的位置离主坐标远，到点（第二次估算该来了、卫星点满 [NO_GPS]）让调用方马上再取一次网络定位（[due]），不等屋里 5 分钟一次；
 *   每个要顶的位置只催一次，估算接连 [MAX_ASKS] 个都对不上就不催了（估算来回跳时不每分钟取一次），报了卫星点、确认了、
 *   估算和主坐标对得上都重新算。
 * - 要不要估算（[wants]）：卫星点 [NO_GPS] 没报过（或从没有主坐标），估算又还没定下来（没有过、等第二次确认、和上一个对不上），
 *   或者定下来（顶上了、和主坐标对得上）已经过了 [SETTLED_EVERY]：静止时呼吸顺带取一次网络定位（11.0.16 实测：一直静止在屋里时
 *   从来拿不到估算，顶不上）。
 * - 最近 [RECENT] 内的可信估算（[recent]）每个上报点都带上（netLat / netLon / netAcc）。
 */
internal class NetFallback(main: MainFix? = null) {

    var main: MainFix? = main
        private set

    /** 主坐标挪远了、换了来源或者隔久了，该存了；调用方存完自己清掉 */
    var changed = false

    private var saved: MainFix? = main
    private var gpsAt: Long? = main?.takeIf { it.gps }?.at

    /** 等第二次确认的估算（没连着卫星锚点时） */
    private var candidate: Seen? = null
    private var latest: Seen? = null

    /** 现在要顶的位置；被卫星锚点挡掉的估算没有 */
    private var target: Target? = null
    private var asked: Target? = null
    private var asks = 0
    private var pending: Position? = null

    /** 估算上一次定下来（确认了能顶、或和主坐标对得上）的时刻；报了卫星点、估算对不上了就清掉 */
    private var settledAt: Long? = null

    private class Seen(val fix: Position, var at: Long)

    /** 要顶的主坐标 [main]，[from] 起值得为它催取（下一个估算能算第二次确认了）；[anchor] 是顶锚点时的那个锚点 */
    private class Target(val main: Position, var from: Long, val anchor: GpsPoint? = null)

    enum class Verdict {
        /** 还没确认，或者卫星点不久前还有，或者估算和卫星锚点对不上 */
        NONE,

        /** 能顶的位置有了、卫星点 [NO_GPS] 没有，但离主坐标不远 */
        CONFIRMED,

        /** 要顶主坐标报 */
        PROMOTED,
    }

    fun trusted(fix: Position): Boolean =
        fix.provider != Provider.GPS && fix.latitude != null && fix.longitude != null &&
            (fix.accuracy ?: Double.MAX_VALUE) <= TRUSTED_ACCURACY

    /** 收到一个可信的估算（先用 [trusted] 筛过）。[anchor]：连着有卫星锚点的 Wi-Fi 时是那个锚点（临时锚点不算），别的时候 null */
    fun estimate(now: Long, fix: Position, anchor: GpsPoint?): Verdict {
        latest = Seen(fix, now)
        val t = (if (anchor != null) byAnchor(now, fix, anchor) else byEstimates(now, fix)) ?: return Verdict.NONE
        target = t
        if (!stale(now)) return Verdict.NONE
        settledAt = now
        if (!far(t.main)) return Verdict.CONFIRMED
        pending = t.main
        return Verdict.PROMOTED
    }

    /** 连着卫星锚点：估算和锚点对得上就能顶锚点（同一个锚点沿用原来的 [Target]，只催一次）；对不上返回 null、挡掉 */
    private fun byAnchor(now: Long, fix: Position, anchor: GpsPoint): Target? {
        candidate = null
        asks = 0
        if (!nearAnchor(fix, anchor)) {
            target = null
            settledAt = null
            return null
        }
        target?.takeIf { it.anchor === anchor }?.let { return it }
        val main = Position(
            time = anchor.time, latitude = anchor.latitude, longitude = anchor.longitude, accuracy = anchor.accuracy, provider = Provider.ANCHOR,
        )
        return Target(main, now, anchor)
    }

    /** 没连着卫星锚点：两个估算确认了返回要顶的位置。第一个、对不上的换成新候选，没隔够 [CONFIRM_GAP] 的不动，都返回 null */
    private fun byEstimates(now: Long, fix: Position): Target? {
        val c = candidate
        if (c == null || !agree(c.fix, fix)) {
            candidate = Seen(fix, now)
            settledAt = null
            if (far(fix)) asks++ else asks = 0
            target = Target(fix.copy(provider = Provider.NETWORK), now + CONFIRM_GAP)
            return null
        }
        if (now - c.at < CONFIRM_GAP) return null
        candidate = Seen(fix, now)
        asks = 0
        return Target(fix.copy(provider = Provider.NETWORK), now + CONFIRM_GAP)
    }

    /** 要顶的位置离主坐标远、到点了、还没为它催过：该马上再取一次网络定位。被卫星锚点挡掉的估算不催（催了只会每分钟取一次） */
    fun due(now: Long): Boolean {
        val t = target ?: return false
        if (t === asked || asks > MAX_ASKS || !far(t.main)) return false
        // 卫星点没报满 [NO_GPS] 和 [estimate] 用同一个判断：正好满的那一刻催了，估算回来还是不顶，这个位置又催过了，要等屋里下一次取
        return now >= t.from && stale(now)
    }

    /** 静止时要不要顺带取一次网络定位：主坐标过时（卫星点 [NO_GPS] 没报过），估算还没定下来或定下来已经过了 [SETTLED_EVERY] */
    fun wants(now: Long): Boolean {
        if (!stale(now)) return false
        val at = settledAt ?: return true
        return now - at >= SETTLED_EVERY
    }

    /** 调用方为现在要顶的位置取了一次网络定位 */
    fun asked() {
        asked = target
    }

    /** 等着当主坐标报的位置（来源 network 或 anchor，精度是它的误差）；取走就清掉 */
    fun takePending(): Position? = pending.also { pending = null }

    /** 最近 [RECENT] 内的可信估算 */
    fun recent(now: Long): Position? = latest?.takeIf { now - it.at in 0..RECENT }?.fix

    /** 报出去了一条（[p] 是最后的上报点）：带主坐标就记下；是卫星点就重新开始算「多久没卫星点」，等着的估算作废 */
    fun reported(now: Long, p: Position) {
        val lat = p.latitude ?: return
        val lon = p.longitude ?: return
        val gps = p.provider == Provider.GPS
        if (gps) {
            gpsAt = now
            pending = null
            asks = 0
            settledAt = null
        }
        val next = MainFix(lat, lon, p.accuracy ?: 0.0, gps, now)
        main = next
        val s = saved
        if (s == null || s.gps != gps || now - s.at >= SAVE_EVERY ||
            distanceMeters(s.latitude, s.longitude, lat, lon) > SAVE_METERS
        ) {
            saved = next
            changed = true
        }
    }

    /** 系统时间被改了 [delta] 毫秒：记的时间跟着挪（和 ReportPolicy.shift 一样） */
    fun shift(delta: Long) {
        gpsAt = gpsAt?.plus(delta)
        candidate?.let { it.at += delta }
        latest?.let { it.at += delta }
        target?.let { it.from += delta }
        settledAt = settledAt?.plus(delta)
        main = main?.let { it.copy(at = it.at + delta) }
        saved = saved?.let { it.copy(at = it.at + delta) }
    }

    private fun stale(now: Long): Boolean {
        val at = gpsAt ?: return true
        return now - at > NO_GPS
    }

    private fun far(fix: Position): Boolean {
        val m = main ?: return true
        val lat = fix.latitude ?: return false
        val lon = fix.longitude ?: return false
        return distanceMeters(m.latitude, m.longitude, lat, lon) > FAR_METERS + (fix.accuracy ?: 0.0)
    }

    /** 估算和卫星锚点对得上：距离减掉两边的精度不超过 [WifiBook.HERE_METERS]（和 [WifiBook.near] 一个尺子） */
    private fun nearAnchor(fix: Position, anchor: GpsPoint): Boolean {
        val d = distanceMeters(anchor.latitude, anchor.longitude, fix.latitude ?: return false, fix.longitude ?: return false)
        return d - anchor.accuracy - (fix.accuracy ?: 0.0) <= WifiBook.HERE_METERS
    }

    private fun agree(a: Position, b: Position): Boolean {
        val d = distanceMeters(a.latitude ?: return false, a.longitude ?: return false, b.latitude ?: return false, b.longitude ?: return false)
        return d <= maxOf(a.accuracy ?: 0.0, b.accuracy ?: 0.0)
    }

    companion object {
        const val NO_GPS = 600_000L
        const val TRUSTED_ACCURACY = 200.0
        const val FAR_METERS = 300.0
        const val CONFIRM_GAP = 60_000L
        const val RECENT = 600_000L
        const val SETTLED_EVERY = 900_000L
        const val MAX_ASKS = 3
        const val SAVE_EVERY = 300_000L
        const val SAVE_METERS = 50.0
    }
}
