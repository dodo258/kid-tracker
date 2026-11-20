package org.traccar.client.health

import kotlinx.serialization.Serializable

/**
 * 厂商后台设置有没有生效（R6 自检清单）：系统读不到的那几项（自启动、关联启动、后台活动、省电策略、允许联网、锁屏不断网）
 * 不让家长手动打勾，按 App 的实际表现判：
 *
 * - [Evidence.REBOOT] 重启后自己恢复：重启后没打开 App，开机广播就把追踪接上了（自启动、关联启动）
 * - [Evidence.BREATH] 锁屏时呼吸准不准：灭屏时呼吸闹钟按时响（后台活动、省电策略、多任务加锁）
 * - [Evidence.SEND] 锁屏时能不能发出去：灭屏时上报发得出去（允许联网、锁屏后不断网）
 *
 * 一开始都是 [Verdict.PENDING]（待验证），攒够证据变 [Verdict.OK] 或 [Verdict.BAD]。系统升级（指纹变了）厂商设置常被重置，全部从头再来；
 * 家长按「去设置」改过某项，那一项也从头再来（[reset]）。
 *
 * 纯函数：平台只负责在事件发生时把墙钟时间、屏幕亮没亮交进来，记录落盘（Android 见 HealthRecorder）。
 */
enum class Evidence { REBOOT, BREATH, SEND }

enum class Verdict { PENDING, OK, BAD }

@Serializable
data class HealthRecord(
    /** 记录对应的系统指纹（Build.FINGERPRINT）：变了说明系统升级过，全部作废 */
    val fingerprint: String = "",
    /** 第一次检查时这次开机的时刻：还在同一次开机里就没法判重启 */
    val firstBoot: Long? = null,
    /** 开机广播接上追踪的那次开机的时刻、开机后几秒收到 */
    val bootHandled: Long? = null,
    val bootSeconds: Int? = null,
    /** 发现重启后开机广播还没来：是哪次开机、什么时候发现的（过 [BOOT_WAIT] 还没来就是没生效） */
    val bootWaiting: Long? = null,
    val bootWaitingSince: Long? = null,
    /** 判出没自己恢复的那次开机 */
    val bootMissed: Long? = null,
    /** 呼吸闹钟挂上的时刻、应该响的时刻（墙钟）；没挂是 null */
    val breathScheduledAt: Long? = null,
    val breathDueAt: Long? = null,
    /** 灭屏时最近几次呼吸：true 准点，false 迟到或没响（最多 [RING] 次） */
    val breath: List<Boolean> = emptyList(),
    /** 最近一次迟到了几分钟 */
    val breathLateMinutes: Int? = null,
    /** 灭屏时最近几次发送的证据：true 发出去了，false 灭屏时连着发不出去、一亮屏就发出去了 */
    val send: List<Boolean> = emptyList(),
    /** 灭屏、有网时连着发不出去几次 */
    val offFailStreak: Int = 0,
    /** 最近一次异常（降级、重建、丢弃、写不进存储，见 Log.fault）：什么时候、哪一类、说明。自检页显示，系统升级不清 */
    val faultAt: Long? = null,
    val faultKind: String? = null,
    val fault: String? = null,
)

/** 一项证据的结论和给家长看的细节（重启后几秒恢复、迟到几分钟） */
data class Judgement(val verdict: Verdict, val detail: Int? = null)

object BackgroundHealth {
    /** 证据最多留这么多次 */
    const val RING = 6

    /** 呼吸迟到超过这么久算不准：5 分钟呼吸再晚 5 分钟，家长页就按 10 分钟判失联了 */
    const val BREATH_LATE = 5 * 60_000L

    /** 该响的闹钟过了这么久还没响，算没响（进程被杀、闹钟被清） */
    const val BREATH_MISSED = 15 * 60_000L

    /** 灭屏、有网时连着发不出去这么多次，亮屏马上就发出去了，才算锁屏断网 */
    const val SEND_FAIL_STREAK = 3

    /** 发现重启后开机广播还没来，再等这么久（家长刚解锁就打开 App 时广播可能还在路上） */
    const val BOOT_WAIT = 3 * 60_000L

    /** 同一次开机算出来的开机时刻会差几毫秒到几秒（墙钟减开机时长） */
    const val BOOT_SAME = 60_000L

    /** 系统指纹变了就从头再来（最近一次异常留着：和厂商设置无关） */
    fun withFingerprint(record: HealthRecord, fingerprint: String): HealthRecord =
        if (record.fingerprint == fingerprint) {
            record
        } else {
            HealthRecord(fingerprint = fingerprint, faultAt = record.faultAt, faultKind = record.faultKind, fault = record.fault)
        }

    /** 记下最近一次异常（只留一条，说明截到 [FAULT_MAX] 字） */
    fun fault(record: HealthRecord, at: Long, kind: String, message: String): HealthRecord =
        record.copy(faultAt = at, faultKind = kind, fault = message.take(FAULT_MAX))

    const val FAULT_MAX = 300

    fun reset(record: HealthRecord, evidence: Evidence, bootTime: Long): HealthRecord = when (evidence) {
        // 改过自启动：从这次开机算起，下次重启再看
        Evidence.REBOOT -> record.copy(
            firstBoot = bootTime, bootHandled = null, bootSeconds = null,
            bootWaiting = null, bootWaitingSince = null, bootMissed = null,
        )
        Evidence.BREATH -> record.copy(breath = emptyList(), breathLateMinutes = null)
        Evidence.SEND -> record.copy(send = emptyList(), offFailStreak = 0)
    }

    fun breathScheduled(record: HealthRecord, now: Long, dueAt: Long): HealthRecord =
        record.copy(breathScheduledAt = now, breathDueAt = dueAt)

    fun breathCancelled(record: HealthRecord): HealthRecord =
        record.copy(breathScheduledAt = null, breathDueAt = null)

    /** 呼吸闹钟响了：灭屏时才算证据（亮屏时系统不会拖） */
    fun breathFired(record: HealthRecord, now: Long, screenOff: Boolean): HealthRecord {
        val due = record.breathDueAt ?: return record
        val cleared = record.copy(breathScheduledAt = null, breathDueAt = null)
        if (!screenOff) return cleared
        val late = now - due
        val onTime = late <= BREATH_LATE
        return cleared.copy(
            breath = push(record.breath, onTime),
            breathLateMinutes = if (onTime) record.breathLateMinutes else (late / 60_000).toInt(),
        )
    }

    /**
     * 一次上报的结果。[online] 是系统说有网（已验证的网络）：没网时发不出去不怪厂商。
     * 灭屏发出去 → 好证据；灭屏有网却连着发不出去，一亮屏就发出去 → 坏证据。
     */
    fun uploaded(record: HealthRecord, success: Boolean, screenOff: Boolean, online: Boolean): HealthRecord = when {
        screenOff && success -> record.copy(send = push(record.send, true), offFailStreak = 0)
        screenOff && online -> record.copy(offFailStreak = record.offFailStreak + 1)
        screenOff -> record
        success && record.offFailStreak >= SEND_FAIL_STREAK -> record.copy(send = push(record.send, false), offFailStreak = 0)
        else -> record.copy(offFailStreak = 0)
    }

    /**
     * 开机广播来了（只算开机，不算覆盖安装）：系统让本 App 开机自己起来了，自启动就是生效的。追踪开着时它顺带接上追踪；
     * 追踪关着（[tracking] 为假）也照样记下这次开机——不然重启后家长打开 App 开追踪再看清单，会被当成广播没来，误判没生效（审查退回第 1 轮）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun bootReceived(record: HealthRecord, bootTime: Long, now: Long, tracking: Boolean): HealthRecord =
        record.copy(
            bootHandled = bootTime,
            bootSeconds = ((now - bootTime) / 1000).toInt().coerceAtLeast(0),
            bootWaiting = null,
            bootWaitingSince = null,
            bootMissed = null,
        )

    /**
     * 看一眼（打开 App、清单刷新时）：补上没响的呼吸闹钟、发现重启后开机广播没来。[bootTime] 是这次开机的时刻，
     * [tracking] 是现在追踪开没开：开着时才去等这次开机的广播（关着时清单看不出也不催），广播到没到和追踪开没开无关。
     */
    fun check(record: HealthRecord, now: Long, bootTime: Long, tracking: Boolean): HealthRecord {
        var r = record
        if (r.firstBoot == null) r = r.copy(firstBoot = bootTime)
        // 闹钟该响没响：重启会清掉闹钟，挂在这次开机之前的不算
        val due = r.breathDueAt
        val scheduled = r.breathScheduledAt
        if (due != null && scheduled != null && now - due > BREATH_MISSED) {
            r = if (scheduled < bootTime) {
                r.copy(breathScheduledAt = null, breathDueAt = null)
            } else {
                r.copy(
                    breathScheduledAt = null, breathDueAt = null,
                    breath = push(r.breath, false), breathLateMinutes = ((now - due) / 60_000).toInt(),
                )
            }
        }
        // 重启后开机广播来没来
        val sameBoot = { a: Long?, b: Long -> a != null && kotlin.math.abs(a - b) < BOOT_SAME }
        if (!tracking || sameBoot(r.firstBoot, bootTime) || sameBoot(r.bootHandled, bootTime) || sameBoot(r.bootMissed, bootTime)) {
            return r
        }
        if (!sameBoot(r.bootWaiting, bootTime)) return r.copy(bootWaiting = bootTime, bootWaitingSince = now)
        val since = r.bootWaitingSince ?: now
        return if (now - since >= BOOT_WAIT) r.copy(bootMissed = bootTime, bootWaiting = null, bootWaitingSince = null) else r
    }

    fun judge(record: HealthRecord, evidence: Evidence): Judgement = when (evidence) {
        Evidence.REBOOT -> when {
            record.bootMissed != null -> Judgement(Verdict.BAD)
            record.bootHandled != null -> Judgement(Verdict.OK, record.bootSeconds)
            else -> Judgement(Verdict.PENDING)
        }
        Evidence.BREATH -> Judgement(ring(record.breath), record.breathLateMinutes)
        Evidence.SEND -> Judgement(ring(record.send))
    }

    /** 最近 [RING] 次里坏了 2 次就是没生效；好了 3 次、最多坏 1 次是生效了；不够就还在等 */
    private fun ring(results: List<Boolean>): Verdict {
        val bad = results.count { !it }
        val good = results.size - bad
        return when {
            bad >= 2 -> Verdict.BAD
            good >= 3 -> Verdict.OK
            else -> Verdict.PENDING
        }
    }

    private fun push(results: List<Boolean>, value: Boolean): List<Boolean> = (results + value).takeLast(RING)
}
