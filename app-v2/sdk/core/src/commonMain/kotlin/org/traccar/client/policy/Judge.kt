package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.motion.Motion
import org.traccar.client.motion.MotionClassifier
import org.traccar.client.sense.Sky
import org.traccar.client.sense.Snapshot

/**
 * 判断层合起来：快照 → 运动判断（motion/）→ Wi-Fi 辅助（[WifiBook]、[AnchorProbe]）→ 定位和上报策略（[ReportPolicy]），再给要报的点填上
 * state / motion / steps / bumps / net / provider（方案第 4 节）；主坐标放卫星点，网络定位放 netLat / netLon（方案 3a 定稿），屋里一直没卫星时
 * 才拿确认过的网络估算或卫星锚点顶上主坐标（[NetFallback]）。全是纯逻辑、同步调用；引擎（TrackerEngine）只管照它说的开关卫星、
 * 取点、上报。时间都是毫秒，[now] 由调用方给（引擎用系统时间，测试和模拟器用场景时间）。[main] 是存下的上一个主坐标（重启接着比）。
 */
class Judge(wifi: WifiMemory = WifiMemory(), restored: Motion? = null, main: MainFix? = null) {

    private val classifier = MotionClassifier()
    private val policy = ReportPolicy()
    private val wifiBook = WifiBook(wifi)
    private val probe = AnchorProbe()
    private val fallback = NetFallback(main)

    init {
        // 重启前是静止：接着静止（只接这一种，见 TrackerEngine），第一次呼吸就报
        if (restored == Motion.STILL) {
            classifier.seedStill()
            policy.seed(Motion.STILL)
        }
    }

    private var last: Snapshot? = null
    private var lastSnapshotAt: Long? = null
    private var ownGps: Snapshot.Fix? = null
    private var stepsBase: Long? = null
    private var bumpSeconds = 0

    /** 探测量过的最后一个卫星点的定位时间：同一个点从快照、定位回调各来一次，只量一次 */
    private var measuredUpTo = Long.MIN_VALUE

    /** Wi-Fi 记忆变了、该存了；调用方存完自己清掉 */
    var wifiChanged = false

    val wifiMemory: WifiMemory get() = wifiBook.memory

    /** 上一个主坐标变了、该存了（[mainFix]）；调用方存完自己清掉 */
    var mainChanged: Boolean
        get() = fallback.changed
        set(value) {
            fallback.changed = value
        }

    val mainFix: MainFix? get() = fallback.main

    val motion: Motion? get() = policy.motion

    /** 要不要开着卫星连续定位：在路上，或者连着锚 Wi-Fi 时在量离锚点多远（这时状态不变、量的点不报，见 [AnchorProbe]） */
    val gps: Boolean get() = policy.gps || probe.active

    /**
     * 静止、飞行模式时传感器只留硬件唤醒（方案 3b）。正在量离锚点多远时不睡：探测的时限靠快照来查，传感器睡了、屋里又定不到星，
     * 卫星会一直开到下一次呼吸（审计：标称 90 秒开了 5 分多钟）
     */
    val sleeping: Boolean get() = (policy.motion == Motion.STILL || policy.motion == Motion.OFF) && !probe.active

    fun onSnapshot(now: Long, raw: Snapshot): Decision {
        val own = ownGps
        val s = if (own != null && own.newerThan(raw.fix)) raw.copy(fix = own) else raw
        last = s
        lastSnapshotAt = now
        expireProbe(now, s)
        // 探测中快照先带来了卫星点（被动定位收到的，常比定位回调早一步，也可能是别的 App 开的卫星）：先和探测开始时的锚点比完，
        // 再让下面的 Wi-Fi 记忆按它挪锚。顺序反过来，锚点先挪到远处，这个点就被当成「还在这里」（审计 11.0.11 第 2 条）
        val early = s.freshGps(MotionClassifier.GPS_FRESH_MILLIS)
            ?.takeIf { probe.active && policy.state != ReportState.MOVING && Sky.mainCoordinate(it.accuracy) }
            ?.let { measured(now, it.toPosition()) }
        // 还连着上次卫星定位时的 Wi-Fi：屋里走动不开卫星。已经在走路坐车时不拿 Wi-Fi 把人拉回屋里（连着的可能是跟着走的热点）
        val anchor = if (policy.state == ReportState.MOVING) null else wifiBook.anchorBssid(s)
        val motion = classifier.update(s, anchor)
        s.stepsTotal?.let { total -> if (stepsBase.let { it == null || total < it }) stepsBase = total }
        if ((s.accStd ?: 0.0) >= MotionClassifier.ACTIVE_ACC) bumpSeconds += SNAPSHOT_SECONDS
        val gps = s.freshGps(MotionClassifier.GPS_FRESH_MILLIS)?.let { f ->
            val lat = f.latitude ?: return@let null
            val lon = f.longitude ?: return@let null
            GpsPoint(f.time, lat, lon, f.accuracy ?: return@let null)
        }
        val anchorAt = wifiBook.anchorPoint
        if (wifiBook.observe(s, classifier.raw, gps, onTheRoad = policy.state == ReportState.MOVING)) wifiChanged = true
        // 锚被清、锚点挪了地方：以前的冷却和重试是对着原来那个地方的，不再挡
        if (wifiBook.anchorPoint !== anchorAt) probe.forget()
        val changed = policy.onMotion(now, motion)
        watchAnchor(now, s)
        val next = if (changed.report != null || changed.fetch) changed else policy.onTick(now, fallback.wants(now))
        // 探测量出来要报的点优先（之后同一刻状态已经跟着变了、刚报过，不会再有第二条要报）
        val out = early?.report?.let { Decision(it, next.fetch) } ?: next
        return confirming(now, out)
    }

    /** 等确认的网络估算到点了（[NetFallback.due]）、这一步又没在取：马上取一次网络定位 */
    private fun confirming(now: Long, d: Decision): Decision {
        if (d.fetch || !fallback.due(now)) return d
        if (!policy.confirm(now).fetch) return d
        fallback.asked()
        return Decision(d.report, fetch = true)
    }

    /**
     * 能用的网络估算（连续定位来的、取回来的）交给 [NetFallback]，连着有卫星锚点的 Wi-Fi 时连同锚点（能不能顶、顶估算还是锚点它来判断）：
     * 确认了就给连着的、还没有锚的 Wi-Fi 当临时锚点（在路上时运动判断先不拿锚改判，停下来以后才起作用）；
     * 要顶主坐标而策略这一刻没别的要报，就单独报一条（[describe] 把它放进主坐标）
     */
    private fun estimated(now: Long, fix: Position?, d: Decision): Decision {
        if (fix == null || !fallback.trusted(fix)) return d
        val anchor = wifiBook.anchorPoint?.takeIf { wifiKind() == WifiKind.ANCHOR && !wifiBook.temporary }
        val verdict = fallback.estimate(now, fix, anchor)
        if (verdict == NetFallback.Verdict.NONE) return d
        val s = last
        val lat = fix.latitude
        val lon = fix.longitude
        if (s != null && lat != null && lon != null &&
            wifiBook.tempAnchor(s, GpsPoint(fix.time, lat, lon, fix.accuracy ?: NetFallback.TRUSTED_ACCURACY))
        ) {
            wifiChanged = true
            probe.forget()
        }
        if (verdict != NetFallback.Verdict.PROMOTED || d.report != null) return d
        return Decision(policy.located(now, fix), d.fetch)
    }

    /** 连着锚 Wi-Fi、没在路上：像在动、或者出现锚这里没见过的基站，就开卫星量一量；在路上了、锚没了就不量了 */
    private fun watchAnchor(now: Long, s: Snapshot) {
        val anchor = if (policy.state == ReportState.MOVING || policy.state == null) null else wifiBook.anchorBssid(s)
        if (probe.active && probe.anchor != anchor) probe.stop()
        val wifi = s.wifiBssid.takeIf { s.net == Snapshot.Net.WIFI }
        val at = wifiBook.anchorPoint
        probe.onSnapshot(now, wifi, s.netLink, anchor, at, classifier.raw, anchor != null && wifiBook.hinted(anchor))
        if (anchor != null && wifiBook.unfamiliarCell(s)) probe.start(now, anchor, at, force = true)
    }

    /** 量到时限还没定到：这一刻的基站当作锚这里的（不然出现一个新基站、屋里又搜不到星，就隔 5 分钟量一次） */
    private fun expireProbe(now: Long, s: Snapshot?) {
        if (probe.expire(now) && s != null && wifiBook.learnCell(s)) wifiChanged = true
    }

    /**
     * 连续定位来的点（[admits] 不收的返回 null 表示丢掉）：连着热点、跟着人走过的 Wi-Fi 时高德的 Wi-Fi 定位不用，头顶被挡的卫星点
     * （[Sky.BLOCKED]，屋里几百米到几公里误差）不当主坐标、不测速、也不算搜到了。[elapsed] 是收到它时的开机时长（判断它新不新鲜用，
     * 见 [Snapshot.ageOf]；不给就按定位时间）
     */
    fun onFix(now: Long, fix: Position, elapsed: Long? = null): Decision? {
        sensorsStopped(now)?.let { return it }
        if (!admits(fix)) return null
        expireProbe(now, last)
        if (fix.provider != Provider.GPS) return estimated(now, fix, policy.onFix(now, fix))
        ownGps = Snapshot.Fix(Provider.GPS, fix.time, fix.latitude, fix.longitude, fix.accuracy, fix.speed, fix.bearing, elapsed)
        if (!probe.active || policy.state == ReportState.MOVING) return policy.onFix(now, fix)
        return measured(now, fix)
    }

    /**
     * 探测时来的卫星点（方案 4.2）：超出锚的覆盖范围（或测速就是在坐车、一边走一边量出在往外走），Wi-Fi 跟着人走了，锚清掉，
     * 在走路坐车就马上转在路上、报这个点；运动判断说没在动（车停着、开得平顺）也把这个点当主坐标报出去，状态不变。
     * 精度好、明显就在锚这里（[WifiBook.near]）：没在走就算还在这里，这个点不报（屋里卫星会漂几十米），冷却；还在走就接着量
     * （[AnchorProbe.walked]，带着随身 Wi-Fi 刚出门时第一个点也在锚附近）。别的接着量，到时限还说不清就过一会儿再量
     */
    private fun measured(now: Long, fix: Position): Decision {
        val lat = fix.latitude ?: return Decision.NONE
        val lon = fix.longitude ?: return Decision.NONE
        val point = GpsPoint(fix.time, lat, lon, fix.accuracy ?: return Decision.NONE)
        if (fix.time <= measuredUpTo) return Decision.NONE
        measuredUpTo = fix.time
        // 和探测开始时的锚点比（[AnchorProbe.origin]），不和量的过程中挪过的比
        val origin = probe.origin ?: wifiBook.anchorPoint
        // 单点速度只认开阔天空下的（[Sky]）：屋里半遮的点带的速度不能证明在坐车
        val fast = Sky.of(fix.accuracy) == Sky.OPEN && (fix.speed ?: 0.0) > MotionClassifier.VEHICLE_SPEED
        val near = point.accuracy <= WifiBook.GOOD_ACCURACY && wifiBook.near(point, origin)
        val walking = classifier.raw.moving
        val leaving = walking && probe.walked(now, point, near)
        if (fast || leaving || wifiBook.beyond(point, origin)) {
            probe.finish(now, here = false)
            if (wifiBook.release(followed = true)) wifiChanged = true
            val motion = if (fast) Motion.VEHICLE else classifier.raw
            if (!motion.moving) return policy.probed(now, fix)
            policy.onFix(now, fix)
            return policy.onMotion(now, motion)
        }
        if (near && !walking) {
            probe.finish(now, here = true)
            last?.let { if (wifiBook.learnCell(it)) wifiChanged = true }
        }
        return Decision.NONE
    }

    /**
     * 取的网络定位回来了；同样不用不该用的 Wi-Fi 定位。连着锚 Wi-Fi 时估算离锚点超过 1 公里（加上估算的精度），不管运动判断怎么说
     * 都量一次（方案 4.3：车载 Wi-Fi 在位置库里多半查不到，估算是跟着车走的基站）
     */
    fun onFetched(now: Long, fix: Position?): Decision {
        expireProbe(now, last)
        val usable = fix?.takeIf { usableFix(it.provider, wifiKind(), followedWifi()) }
        val anchor = last?.let(wifiBook::anchorBssid)
        val at = wifiBook.anchorPoint
        if (usable != null && anchor != null && at != null && policy.state != ReportState.MOVING) {
            val lat = usable.latitude
            val lon = usable.longitude
            if (lat != null && lon != null &&
                distanceMeters(at.latitude, at.longitude, lat, lon) > AnchorProbe.NET_FAR_METERS + (usable.accuracy ?: 0.0)
            ) {
                probe.start(now, anchor, at, force = true)
            }
        }
        return estimated(now, usable, policy.onFetched(now, usable))
    }

    /** 系统时间被改了 [delta] 毫秒：策略和探测里记的时间跟着挪（见 [ReportPolicy.shift]） */
    fun clockShifted(delta: Long) {
        lastSnapshotAt = lastSnapshotAt?.plus(delta)
        // 有的定位源的卫星点时间跟着系统时间走：往回改了不能让以后的点都当成量过的
        measuredUpTo = Long.MIN_VALUE
        policy.shift(delta)
        probe.shift(delta)
        fallback.shift(delta)
    }

    /**
     * 心跳闹钟（静止、屋里时 CPU 可能睡着，没有快照）。开追踪以来一份快照都没有（手机没有加速度计、传感器服务坏了）时，
     * 第一次呼吸就按「在附近活动」接着报：取网络定位、5 分钟一次。不然判断层一直不知道运动状态、一个点都不报
     */
    fun onTick(now: Long): Decision {
        expireProbe(now, last)
        if (last == null && policy.motion == null) return policy.onMotion(now, Motion.FIDGET)
        sensorsStopped(now)?.let { return it }
        return confirming(now, policy.onTick(now, fallback.wants(now)))
    }

    /**
     * 走路、坐车途中传感器彻底不交快照了（传感器服务坏了、系统停了监听）：超过 [SENSORS_STALE_MILLIS] 就降成「在附近活动」、关卫星，
     * 不然一直按最后的判断开着卫星报。静止、飞行模式时本来就没有快照，不算；快照再来时照常判断
     */
    private fun sensorsStopped(now: Long): Decision? {
        val at = lastSnapshotAt ?: return null
        if (policy.motion?.moving != true || now - at <= SENSORS_STALE_MILLIS) return null
        return policy.onMotion(now, Motion.FIDGET)
    }

    /**
     * 把要报的一条做成上报的点：带上状态、运动摘要、联网方式；步数和晃动从这条重新算。卫星点放主坐标；网络定位一般不放主坐标
     * （只报时间，服务器沿用上一个位置），估算放 netLat / netLon / netAcc / netSource。屋里一直没卫星时 [NetFallback] 要顶的位置
     * 放进这条的主坐标：确认过的估算（来源 network）或卫星锚点（来源 anchor），精度是它的误差。不管报的是什么，最近 10 分钟内可信的估算都带上
     */
    fun describe(out: Outgoing): Position {
        val s = last
        val steps = s?.stepsTotal?.let { total -> stepsBase?.let { (total - it).toInt() } }
        stepsBase = s?.stepsTotal ?: stepsBase
        val bumps = bumpSeconds
        bumpSeconds = 0
        // 网络定位只报时间（报出的那一刻），估算放附加字段
        val base = out.fix?.let { if (it.provider == Provider.GPS) it else it.satelliteOnly().copy(time = out.time) }
            ?: Position(time = out.time)
        val main = if (base.latitude == null) fallback.takePending()?.let { base.estimatedAt(it) } ?: base else base
        val p = annotate(withRecent(main, out.time)).copy(steps = steps, bumps = bumps)
        fallback.reported(out.time, p)
        return p
    }

    /**
     * 给 App 里「发送位置」那样不走策略的点带上现在的状态（不动运动摘要）。和连续定位来的点一个准入（[admits]）：头顶被挡的卫星点、
     * 跟着人走过的 Wi-Fi 和热点下的 Wi-Fi 定位都只报时间（审计 11.0.11 第 1、9 条）；同样只有卫星点放主坐标，最近的可信估算带上
     */
    fun current(position: Position): Position {
        val admitted = if (admits(position)) position else position.timeOnly()
        return annotate(withRecent(admitted.satelliteOnly(), position.time))
    }

    /** 自己没带估算的点带上最近 10 分钟内可信的那个（[NetFallback.recent]） */
    private fun withRecent(p: Position, now: Long): Position {
        if (p.netLat != null) return p
        val e = fallback.recent(now) ?: return p
        return p.copy(netLat = e.latitude, netLon = e.longitude, netAcc = e.accuracy, netSource = e.provider ?: Provider.NETWORK)
    }

    /** 带上现在的状态、联网方式 */
    private fun annotate(p: Position): Position {
        val kind = wifiKind()
        return p.copy(
            state = policy.state?.wire,
            motion = policy.motion?.wire,
            net = netLabel(last?.net, kind),
            wifiSame = if (kind == WifiKind.ANCHOR || kind == WifiKind.OTHER) kind == WifiKind.ANCHOR else null,
        )
    }

    private fun wifiKind(): WifiKind = last?.let(wifiBook::kind) ?: WifiKind.NONE

    /** 这个定位能用：卫星点要能当主坐标（[Sky.mainCoordinate]），Wi-Fi 定位要不是热点、跟着人走过的 Wi-Fi 下的（[usableFix]） */
    private fun admits(fix: Position): Boolean =
        if (fix.provider == Provider.GPS) Sky.mainCoordinate(fix.accuracy) else usableFix(fix.provider, wifiKind(), followedWifi())

    /** 连着的 Wi-Fi 跟着人走过（[WifiBook.followed]） */
    private fun followedWifi(): Boolean = last?.let(wifiBook::followed) ?: false

    companion object {
        private const val SNAPSHOT_SECONDS = 2

        /** 在动时这么久没快照算传感器停了（正常 2 秒一份，息屏批量约 10 秒一批） */
        const val SENSORS_STALE_MILLIS = 180_000L
    }
}

/** 只留时间（和联网、状态这些），坐标、速度、来源都去掉 */
internal fun Position.timeOnly(): Position =
    copy(latitude = null, longitude = null, accuracy = null, altitude = null, speed = null, bearing = null, provider = null)

/** 快照里的卫星点当定位回调来的点用（探测时拿它量） */
private fun Snapshot.Fix.toPosition(): Position =
    Position(time = time, latitude = latitude, longitude = longitude, accuracy = accuracy, speed = speed, bearing = bearing, provider = Provider.GPS)

/** 比 [other] 新：两边都有开机时长就比它（改了系统时间也准），不然比定位时间 */
private fun Snapshot.Fix.newerThan(other: Snapshot.Fix?): Boolean {
    other ?: return true
    val a = elapsed
    val b = other.elapsed
    return if (a != null && b != null) a > b else time > other.time
}

/** 主坐标只放卫星点：别的定位（Wi-Fi、基站、来源不明）挪到 netLat / netLon / netAcc / netSource，主坐标空着 */
internal fun Position.satelliteOnly(): Position {
    if (latitude == null || longitude == null || provider == Provider.GPS) return this
    return copy(
        latitude = null,
        longitude = null,
        accuracy = null,
        altitude = null,
        speed = null,
        bearing = null,
        provider = null,
        netLat = latitude,
        netLon = longitude,
        netAcc = accuracy,
        netSource = provider ?: Provider.NETWORK,
    )
}

/** 主坐标换成 [NetFallback] 顶上的位置 [e]（确认过的网络估算或卫星锚点）：来源、精度用它的，时间还是这条上报的 */
internal fun Position.estimatedAt(e: Position): Position =
    copy(latitude = e.latitude, longitude = e.longitude, accuracy = e.accuracy, provider = e.provider)
