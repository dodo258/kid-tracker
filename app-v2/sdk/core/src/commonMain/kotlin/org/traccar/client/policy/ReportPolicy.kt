package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.motion.Motion

/** 上报的 state（家长页认这三个）：在路上、在附近活动、静止 */
enum class ReportState(val wire: String) {
    MOVING("moving"),
    ZONE("zone"),
    STILL("still"),
}

/** 运动判断对应的上报状态；飞行模式不报 */
fun Motion.reportState(): ReportState? = when (this) {
    Motion.WALK, Motion.VEHICLE -> ReportState.MOVING
    Motion.FIDGET -> ReportState.ZONE
    Motion.STILL -> ReportState.STILL
    Motion.OFF -> null
}

/** 上报的 motion 字段 */
val Motion.wire: String get() = name.lowercase()

/** 要报的一条：[fix] 为 null 时只报时间（心跳、呼吸） */
data class Outgoing(val time: Long, val fix: Position?, val reason: String)

/** 策略这一步要引擎做的事：报一条、取一次网络定位（不开卫星） */
data class Decision(val report: Outgoing? = null, val fetch: Boolean = false) {
    companion object {
        val NONE = Decision()
    }
}

/**
 * 定位和上报策略（方案第 3 节 policy/）：运动判断 + 收到的点 + 时间 → 开不开卫星、什么时候报哪一个点。纯逻辑，引擎负责执行
 * （开关连续定位、取点、上报），模拟器 tools/simulator/reporter.py 是同一套规则的 Python 版（金样本对齐，见 SimulatorGoldenTest）。
 *
 * - 走路 / 坐车（[ReportState.MOVING]）：立刻开卫星，每 [MOVING_EVERY] 报一个最新的点；刚开卫星 [GPS_WAIT] 还没定到就先报网络点
 *   （带来源），卫星接着搜；定到过又丢了（进楼、进站）不再等 30 秒，最新的卫星点过了 [FRESH] 就拿网络点顶上，免得家长页那边
 *   空着往前外推；什么新点都没有，每 [NO_FIX_EVERY] 报一个只带时间的点。卫星连续 [GPS_GIVE_UP] 搜不到（屋里、地铁）就停 [GPS_RETRY]
 *   再试，停着时每 [NO_FIX_EVERY] 取一次网络定位报上去（方案 3b：搜不到就停、隔一会再试）。
 * - 屋里走动（[ReportState.ZONE]）：不开卫星，每 [ZONE_EVERY] 取一次网络定位报上去，取不到只报时间。
 * - 静止（[ReportState.STILL]）：关卫星，每 [BREATH_EVERY] 呼吸一次（只报时间）；刚转静止时报一个最后位置。屋里一直没卫星、主坐标要顶
 *   （[onTick] 的 locate，见 [NetFallback.wants]）时呼吸那一刻顺带取一次网络定位，这次呼吸等取回来带着它报（取不到照样只报时间；
 *   11.0.16 实测：一直静止在屋里从来拿不到估算，顶不上）；静止时取网络定位每小时最多 [STILL_FETCH_MAX] 次（进静止那一次也算，不受这个限）。
 * - 飞行模式：全停；退出时马上报一次。
 * - 状态一变马上报一条：手里有 [FRESH] 内没报过的点就带上，没有就只报时间。从走路 / 坐车停下、刚开始、退出飞行模式转到屋里走动或静止时
 *   再取一次网络定位补上位置；屋里走动和静止之间互转不取（人没换地方，再取一个只会让家长页的圆点原地抖），屋里的下一次照常 5 分钟后。
 * - 屋里一直没卫星、网络估算要再取一次确认（[confirm]，见 [NetFallback]）：屋里走动、静止时马上取；确认过的估算策略又没别的要报时单独报（[located]）。
 *
 * 时间都是毫秒；[onTick] 由引擎每份传感器快照（约 2 秒，批量时一批几份）和心跳闹钟调。
 */
class ReportPolicy {

    var motion: Motion? = null
        private set

    val state: ReportState? get() = motion?.reportState()

    /** 现在要不要开着卫星连续定位 */
    val gps: Boolean get() = state == ReportState.MOVING && gpsPausedAt == null

    private var latestGps: Seen? = null
    private var latestNet: Seen? = null
    /** 报过的点的定位时间，按来源分开记：网络定位跟手机时间走、卫星点用卫星时间，混着比的话改快钟后一个网络点就压住之后的卫星点 */
    private var reportedGpsTime = Long.MIN_VALUE
    private var reportedNetTime = Long.MIN_VALUE
    private var lastReportAt = Long.MIN_VALUE / 2
    private var searchSince: Long? = null
    private var gpsPausedAt: Long? = null
    private var nextZoneAt = 0L
    private var nextBreathAt = 0L
    private var fetchFor: ReportState? = null
    /** 正在取的网络定位是静止呼吸顺带取的：回来时报这次呼吸 */
    private var breathing = false
    /** 静止时取网络定位的时刻，只留最近一小时（[STILL_FETCH_MAX]） */
    private val stillFetches = ArrayDeque<Long>()

    private var lastNow: Long? = null

    private class Seen(val fix: Position, val at: Long)

    /** 重启前的运动状态（只用静止）：不报状态变化，第一次 [onTick] 就呼吸 */
    fun seed(restored: Motion) {
        motion = restored
        nextBreathAt = 0
    }

    /**
     * 时间被往回改了（手动改时间、换时区不算，时区不影响毫秒时间）：手里记的「上次什么时候报的、下次几点报」全部跟着往回挪，
     * 照样按间隔报。不挪的话下一次要等到时间追上原来那个点（往回改一天就一天不报）。往前改不用管：只是提早报一次。
     */
    private fun clock(now: Long) {
        val before = lastNow
        if (before != null && now < before) shift(now - before)
        lastNow = now
    }

    /**
     * 系统时间被改了 [delta] 毫秒（调用方拿开机时长比出来的，准）：手里记的时间全部跟着挪，节奏不变。
     * 调用方量不出来时 [clock] 只能在下一次调用时发现往回改、当作中间没过时间来挪（会晚一个间隔）。
     */
    fun shift(delta: Long) {
        if (delta == 0L) return
        lastNow = lastNow?.plus(delta)
        lastReportAt += delta
        searchSince = searchSince?.plus(delta)
        gpsPausedAt = gpsPausedAt?.plus(delta)
        nextZoneAt += delta
        nextBreathAt += delta
        latestGps = latestGps?.let { Seen(it.fix, it.at + delta) }
        latestNet = latestNet?.let { Seen(it.fix, it.at + delta) }
        for (i in stillFetches.indices) stillFetches[i] += delta
        // 定位点的时间有的跟系统时间走（网络定位，有的定位源的卫星点也是）：不再拿它比新旧，免得新点被当成报过的
        reportedGpsTime = Long.MIN_VALUE
        reportedNetTime = Long.MIN_VALUE
    }

    fun onMotion(now: Long, next: Motion): Decision {
        clock(now)
        val before = motion
        motion = next
        val state = next.reportState() ?: run {
            searchSince = null
            gpsPausedAt = null
            fetchFor = null
            breathing = false
            return Decision.NONE
        }
        val from = before?.reportState()
        if (before != null && from == state) return Decision.NONE
        return enter(now, state, moved = from == null || from == ReportState.MOVING)
    }

    fun onFix(now: Long, fix: Position): Decision {
        clock(now)
        val seen = Seen(fix, now)
        if (fix.provider == Provider.GPS) latestGps = seen else latestNet = seen
        return if (state == ReportState.MOVING) moving(now) else Decision.NONE
    }

    /**
     * 连着锚 Wi-Fi 探测时卫星量出人已经离开锚这里，运动判断却没说在动（车停着、开得平顺）：这个卫星点当主坐标马上报，状态不变
     * （审计：不报的话下一份快照把远处记成新锚，之后屋里走动不开卫星，主坐标一直停在原地）
     */
    fun probed(now: Long, fix: Position): Decision {
        clock(now)
        latestGps = Seen(fix, now)
        return Decision(report(now, fix, "probe"))
    }

    /** 确认过的网络估算要当主坐标（[NetFallback]），这一刻策略又没别的要报：单独报一条 */
    fun located(now: Long, fix: Position): Outgoing {
        clock(now)
        latestNet = Seen(fix, now)
        return report(now, fix, "estimate")
    }

    /**
     * 网络估算要再取一次确认（[NetFallback.due]）：屋里走动、静止时马上取一次，结果照这个状态的规矩报（屋里的下一次从现在起 5 分钟后）；
     * 在路上自己会取，已经在取也不另取
     */
    fun confirm(now: Long): Decision {
        clock(now)
        val s = state
        if (fetchFor != null || s == null || s == ReportState.MOVING) return Decision.NONE
        if (s == ReportState.STILL && !stillFetchAllowed(now)) return Decision.NONE
        if (s == ReportState.ZONE) nextZoneAt = now + ZONE_EVERY
        return fetch(now)
    }

    /** 引擎取的网络定位回来了（[fix] 为 null 是没取到） */
    fun onFetched(now: Long, fix: Position?): Decision {
        clock(now)
        val purpose = fetchFor
        val breath = breathing
        fetchFor = null
        breathing = false
        if (fix != null && fix.provider != Provider.GPS) latestNet = Seen(fix, now)
        if (purpose == null || purpose != state) return Decision.NONE
        return when (purpose) {
            ReportState.STILL -> if (breath) Decision(report(now, fix, "breath")) else fix?.let { Decision(report(now, it, "last")) } ?: Decision.NONE
            ReportState.ZONE -> Decision(report(now, fix, "zone"))
            ReportState.MOVING -> Decision(report(now, fix, "nogps"))
        }
    }

    /** [locate]：主坐标要顶（[NetFallback.wants]），静止呼吸时顺带取一次网络定位 */
    fun onTick(now: Long, locate: Boolean = false): Decision {
        clock(now)
        return tick(now, locate)
    }

    private fun tick(now: Long, locate: Boolean): Decision = when (state) {
        ReportState.MOVING -> moving(now)
        ReportState.ZONE -> if (now >= nextZoneAt && fetchFor == null) {
            nextZoneAt = now + ZONE_EVERY
            fetch(now)
        } else {
            Decision.NONE
        }
        ReportState.STILL -> if (now >= nextBreathAt) {
            nextBreathAt = now + BREATH_EVERY
            if (locate && fetchFor == null && stillFetchAllowed(now)) {
                breathing = true
                fetch(now)
            } else {
                Decision(report(now, null, "breath"))
            }
        } else {
            Decision.NONE
        }
        null -> Decision.NONE
    }

    /** [moved]：之前在走路坐车（或刚开始、刚退出飞行模式），位置可能变了 */
    private fun enter(now: Long, state: ReportState, moved: Boolean): Decision {
        searchSince = if (state == ReportState.MOVING) now else null
        gpsPausedAt = null
        fetchFor = null
        breathing = false
        val fix = listOfNotNull(latestGps, latestNet)
            .filter { now - it.at <= FRESH && unreported(it.fix) }
            .maxByOrNull { it.at }?.fix // 按收到的先后挑：两种来源的定位时间不是一个钟
        val out = report(now, fix, "state")
        return when (state) {
            ReportState.MOVING -> Decision(out)
            ReportState.ZONE -> {
                nextZoneAt = now + ZONE_EVERY
                if (fix == null && moved) fetch(now, out) else Decision(out)
            }
            ReportState.STILL -> {
                nextBreathAt = now + BREATH_EVERY
                if (fix == null && moved) fetch(now, out) else Decision(out)
            }
        }
    }

    private fun moving(now: Long): Decision {
        val since = searchSince ?: now
        val gpsSeen = latestGps?.at?.takeIf { it >= since }
        val lastGpsAt = gpsSeen ?: since
        val netAllowed = if (gpsSeen != null) now - gpsSeen > FRESH else now - since >= GPS_WAIT
        val paused = gpsPausedAt
        if (paused != null && now - paused >= GPS_RETRY) {
            gpsPausedAt = null
            searchSince = now
        } else if (paused == null && now - lastGpsAt >= GPS_GIVE_UP) {
            gpsPausedAt = now
        }
        if (now - lastReportAt < MOVING_EVERY) return Decision.NONE
        fresh(latestGps, now)?.let { return Decision(report(now, it, "gps")) }
        if (netAllowed) fresh(latestNet, now)?.let { return Decision(report(now, it, "net")) }
        if (now - lastReportAt < NO_FIX_EVERY) return Decision.NONE
        if (gpsPausedAt == null) return Decision(report(now, null, "nofix"))
        return if (fetchFor == null) fetch(now) else Decision.NONE
    }

    private fun fresh(seen: Seen?, now: Long): Position? =
        seen?.takeIf { now - it.at <= FRESH && unreported(it.fix) }?.fix

    private fun unreported(fix: Position): Boolean =
        fix.time > if (fix.provider == Provider.GPS) reportedGpsTime else reportedNetTime

    private fun fetch(now: Long, with: Outgoing? = null): Decision {
        fetchFor = state
        if (state == ReportState.STILL) stillFetches.addLast(now)
        return Decision(with, fetch = true)
    }

    /** 静止时最近一小时取网络定位还没到 [STILL_FETCH_MAX] 次 */
    private fun stillFetchAllowed(now: Long): Boolean {
        while (stillFetches.isNotEmpty() && now - stillFetches.first() >= HOUR) stillFetches.removeFirst()
        return stillFetches.size < STILL_FETCH_MAX
    }

    private fun report(now: Long, fix: Position?, reason: String): Outgoing {
        lastReportAt = now
        if (fix != null) {
            if (fix.provider == Provider.GPS) reportedGpsTime = fix.time else reportedNetTime = fix.time
        }
        return Outgoing(now, fix, reason)
    }

    companion object {
        const val MOVING_EVERY = 10_000L
        const val GPS_WAIT = 30_000L
        const val NO_FIX_EVERY = 60_000L
        const val GPS_GIVE_UP = 180_000L
        const val GPS_RETRY = 120_000L
        const val ZONE_EVERY = 300_000L
        const val BREATH_EVERY = 300_000L
        const val STILL_FETCH_MAX = 4
        private const val HOUR = 3_600_000L

        /** 状态变化、在动时：这么久以内收到的点算「现在的」 */
        const val FRESH = 10_000L
    }
}
