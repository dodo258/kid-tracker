package org.traccar.client.alert

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.sense.Sky

/**
 * 告警报给服务器的 `alarm` 值（OsmAnd 协议的 alarm 参数，Traccar 的 Position.ALARM_*）。和根目录旧 App 一样，
 * 家长页（parent-web/src/domain/alarms.ts）和服务器通知模板（server/traccar-templates/notifications/）按它出文案。
 */
object Alarm {
    const val SOS = "sos"
    const val TRACKING_OFF = "trackingOff"
    const val LOW_BATTERY = "lowBattery"
    const val POWER_OFF = "powerOff"

    /** 定位被关、定位权限被收回（具体哪一样只写日志） */
    const val GENERAL = "general"

    /** 防护被动了（R6）：设备管理员被停用（reason=adminDisabled）、App 密码连续输错 5 次（reason=passwordFailures） */
    const val TAMPERING = "tampering"

    /** 看门狗第三级：重建定位源、重建整套跟踪都没救回来（reason=watchdog）。Traccar 的标准告警 fault */
    const val FAULT = "fault"
}

/** 告警带的坐标最多这么旧（和现在比）：再旧就是旧点冒充现在的位置（审计 M4），只报时间 */
const val ALERT_FIX_FRESH = 30_000L

/** 定位时间比现在晚这么多以内还算（系统时间和卫星时间的小偏差）；再多说明时间被改过，不信 */
const val ALERT_FIX_SKEW = 5_000L

/**
 * 告警能带的位置：只有新鲜的卫星点（[ALERT_FIX_FRESH] 内）、能当主坐标的（[Sky.mainCoordinate]）。Wi-Fi、基站这类网络定位、来源不明、
 * 头顶被挡的、旧点、时间对不上的都不要，返回 null，告警只报时间（服务器沿用上一个位置并标过时）。
 */
fun freshGpsFix(fix: Position?, now: Long): Position? {
    if (fix == null || fix.provider != Provider.GPS) return null
    if (fix.latitude == null || fix.longitude == null) return null
    if (!Sky.mainCoordinate(fix.accuracy)) return null
    val age = now - fix.time
    return fix.takeIf { age in -ALERT_FIX_SKEW..ALERT_FIX_FRESH }
}

/**
 * 低电告警：电量降到 [threshold] 及以下、没在充电时报一次；之后回到 [rearmLevel] 以上或插上充电才会再报，
 * 电量在 10% 上下跳也不重复报（根目录旧 App 同样的门槛）。
 */
class LowBatteryPolicy(
    private val threshold: Int = 10,
    private val rearmLevel: Int = 15,
) {
    data class Decision(val report: Boolean, val reported: Boolean)

    /** [reported]：这一轮低电已经报过（调用方落盘，进程重启后不重报） */
    fun next(reported: Boolean, level: Int, charging: Boolean): Decision = when {
        charging || level >= rearmLevel -> Decision(report = false, reported = false)
        level <= threshold && !reported -> Decision(report = true, reported = true)
        else -> Decision(report = false, reported = reported)
    }
}

/** 定位用不了的原因：写进日志；`alarm` 都报 [Alarm.GENERAL] */
enum class LocationProblem(val reason: String) {
    LocationOff("locationOff"),
    PermissionRevoked("permissionRevoked"),
    PreciseLocationOff("preciseLocationOff"),
    BackgroundPermissionRevoked("backgroundPermissionRevoked"),
}

object LocationAlarmPolicy {

    /** [background] 在不需要后台定位权限的系统（Android 10 以前）上传 true */
    fun problems(locationEnabled: Boolean, fine: Boolean, coarse: Boolean, background: Boolean): Set<LocationProblem> =
        buildSet {
            if (!locationEnabled) add(LocationProblem.LocationOff)
            when {
                !fine && !coarse -> add(LocationProblem.PermissionRevoked)
                !fine -> add(LocationProblem.PreciseLocationOff)
            }
            if ((fine || coarse) && !background) add(LocationProblem.BackgroundPermissionRevoked)
        }

    data class Decision(val report: Set<LocationProblem>, val reported: Set<LocationProblem>)

    /** 每个问题出现时报一次；问题消失后从 [reported] 里去掉，再出现会再报 */
    fun next(reported: Set<LocationProblem>, current: Set<LocationProblem>): Decision =
        Decision(report = current - reported, reported = current)
}
