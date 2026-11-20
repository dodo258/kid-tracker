package org.traccar.client.health

import kotlin.test.Test
import kotlin.test.assertEquals
import org.traccar.client.health.BackgroundHealth.breathFired
import org.traccar.client.health.BackgroundHealth.breathScheduled
import org.traccar.client.health.BackgroundHealth.check
import org.traccar.client.health.BackgroundHealth.judge
import org.traccar.client.health.BackgroundHealth.uploaded

class BackgroundHealthTest {

    private val minute = 60_000L
    private val boot1 = 1_800_000_000_000L
    private val boot2 = boot1 + 24 * 60 * minute

    private fun verdict(r: HealthRecord, e: Evidence) = judge(r, e).verdict

    /** 挂一次 5 分钟的呼吸，过 [lateMinutes] 分钟响 */
    private fun breathe(r: HealthRecord, at: Long, lateMinutes: Long, screenOff: Boolean = true): HealthRecord {
        val due = at + 5 * minute
        return breathFired(breathScheduled(r, at, due), due + lateMinutes * minute, screenOff)
    }

    @Test
    fun everythingStartsPending() {
        val r = check(HealthRecord(fingerprint = "f"), boot1 + minute, boot1, tracking = true)
        Evidence.entries.forEach { assertEquals(Verdict.PENDING, verdict(r, it)) }
    }

    @Test
    fun breathOnTimeWhileScreenOffBecomesOk() {
        var r = HealthRecord()
        repeat(2) { r = breathe(r, boot1 + it * 10 * minute, lateMinutes = 0) }
        assertEquals(Verdict.PENDING, verdict(r, Evidence.BREATH))
        r = breathe(r, boot1 + 30 * minute, lateMinutes = 1)
        assertEquals(Verdict.OK, verdict(r, Evidence.BREATH))
    }

    @Test
    fun breathWhileScreenOnIsNoEvidence() {
        var r = HealthRecord()
        repeat(5) { r = breathe(r, boot1 + it * 10 * minute, lateMinutes = 20, screenOff = false) }
        assertEquals(Verdict.PENDING, verdict(r, Evidence.BREATH))
        assertEquals(emptyList(), r.breath)
    }

    @Test
    fun twoLateBreathsAreBadAndShowMinutes() {
        var r = HealthRecord()
        repeat(3) { r = breathe(r, boot1 + it * 10 * minute, lateMinutes = 0) }
        r = breathe(r, boot1 + 40 * minute, lateMinutes = 6)
        // 一次迟到（华为偶尔推迟）不翻
        assertEquals(Verdict.OK, verdict(r, Evidence.BREATH))
        r = breathe(r, boot1 + 60 * minute, lateMinutes = 9)
        assertEquals(Judgement(Verdict.BAD, 9), judge(r, Evidence.BREATH))
    }

    @Test
    fun alarmThatNeverFiresCountsAsLate() {
        var r = HealthRecord(firstBoot = boot1)
        r = breathScheduled(r, boot1 + minute, boot1 + 6 * minute)
        r = check(r, boot1 + 25 * minute, boot1, tracking = true)
        r = breathScheduled(r, boot1 + 26 * minute, boot1 + 31 * minute)
        r = check(r, boot1 + 50 * minute, boot1, tracking = true)
        assertEquals(Verdict.BAD, verdict(r, Evidence.BREATH))
    }

    @Test
    fun alarmClearedByRebootIsNotLate() {
        var r = HealthRecord(firstBoot = boot1)
        r = breathScheduled(r, boot2 - 2 * minute, boot2 + 3 * minute)
        r = check(r, boot2 + 30 * minute, boot2, tracking = false)
        assertEquals(emptyList(), r.breath)
        assertEquals(null, r.breathDueAt)
    }

    @Test
    fun sendWhileScreenOffBecomesOk() {
        var r = HealthRecord()
        repeat(3) { r = uploaded(r, success = true, screenOff = true, online = true) }
        assertEquals(Verdict.OK, verdict(r, Evidence.SEND))
    }

    @Test
    fun screenOffNetworkCutIsBad() {
        var r = HealthRecord()
        repeat(2) {
            repeat(3) { r = uploaded(r, success = false, screenOff = true, online = true) }
            r = uploaded(r, success = true, screenOff = false, online = true)
        }
        assertEquals(Verdict.BAD, verdict(r, Evidence.SEND))
    }

    @Test
    fun realOutageIsNotBlamedOnTheVendor() {
        var r = HealthRecord()
        // 没网时灭屏发不出去：不算
        repeat(5) { r = uploaded(r, success = false, screenOff = true, online = false) }
        r = uploaded(r, success = true, screenOff = false, online = true)
        // 亮屏也发不出去：说明是网的问题，清零
        repeat(3) { r = uploaded(r, success = false, screenOff = true, online = true) }
        r = uploaded(r, success = false, screenOff = false, online = true)
        r = uploaded(r, success = true, screenOff = false, online = true)
        assertEquals(emptyList(), r.send)
        assertEquals(Verdict.PENDING, verdict(r, Evidence.SEND))
    }

    @Test
    fun sameBootIsPendingRebootHandledIsOk() {
        var r = check(HealthRecord(), boot1 + minute, boot1, tracking = true)
        r = check(r, boot1 + 60 * minute, boot1 + 300, tracking = true)
        assertEquals(Verdict.PENDING, verdict(r, Evidence.REBOOT))
        r = BackgroundHealth.bootReceived(r, boot2, boot2 + 80_000, tracking = true)
        r = check(r, boot2 + 10 * minute, boot2 + 500, tracking = true)
        assertEquals(Judgement(Verdict.OK, 80), judge(r, Evidence.REBOOT))
    }

    @Test
    fun rebootWithoutBroadcastIsBadAfterWaiting() {
        var r = check(HealthRecord(), boot1 + minute, boot1, tracking = true)
        // 刚解锁就打开 App：广播可能还在路上，先等
        r = check(r, boot2 + 30_000, boot2, tracking = true)
        assertEquals(Verdict.PENDING, verdict(r, Evidence.REBOOT))
        r = check(r, boot2 + 30_000 + BackgroundHealth.BOOT_WAIT, boot2, tracking = true)
        assertEquals(Verdict.BAD, verdict(r, Evidence.REBOOT))
        // 改了设置后下次重启自己接上了
        val boot3 = boot2 + 60 * minute
        r = BackgroundHealth.bootReceived(r, boot3, boot3 + 40_000, tracking = true)
        assertEquals(Verdict.OK, verdict(r, Evidence.REBOOT))
    }

    @Test
    fun lateBroadcastStillCountsAsOk() {
        var r = check(HealthRecord(), boot1 + minute, boot1, tracking = true)
        r = check(r, boot2 + 30_000, boot2, tracking = true)
        r = BackgroundHealth.bootReceived(r, boot2, boot2 + 90_000, tracking = true)
        r = check(r, boot2 + 10 * minute, boot2, tracking = true)
        assertEquals(Verdict.OK, verdict(r, Evidence.REBOOT))
    }

    @Test
    fun broadcastWithTrackingOffStillCountsAsAutostart() {
        // 审查退回第 1 轮：重启前追踪关着、开机广播照常到了，重启后家长打开 App 开追踪再看清单，不能判「没自己恢复」。
        // 开机广播到没到只看自启动，和追踪开没开无关：到了就说明系统让本 App 开机自己起来
        var r = check(HealthRecord(), boot1 + minute, boot1, tracking = false)
        r = BackgroundHealth.bootReceived(r, boot2, boot2 + 30_000, tracking = false)
        r = check(r, boot2 + 5 * minute, boot2, tracking = true)
        r = check(r, boot2 + 5 * minute + BackgroundHealth.BOOT_WAIT, boot2, tracking = true)
        assertEquals(Verdict.OK, verdict(r, Evidence.REBOOT))
    }

    @Test
    fun noBroadcastWhileTrackingOffIsJudgedOnceTrackingIsOn() {
        // 重启前追踪关着、开机广播也没来：家长开追踪后照样等 BOOT_WAIT，还没来就是自启动没开
        var r = check(HealthRecord(), boot1 + minute, boot1, tracking = false)
        r = check(r, boot2 + 30_000, boot2, tracking = false)
        assertEquals(Verdict.PENDING, verdict(r, Evidence.REBOOT))
        r = check(r, boot2 + 5 * minute, boot2, tracking = true)
        r = check(r, boot2 + 5 * minute + BackgroundHealth.BOOT_WAIT, boot2, tracking = true)
        assertEquals(Verdict.BAD, verdict(r, Evidence.REBOOT))
    }

    @Test
    fun systemUpdateAndResetStartOver() {
        var r = HealthRecord(fingerprint = "old")
        repeat(3) { r = uploaded(r, success = true, screenOff = true, online = true) }
        repeat(3) { r = breathe(r, boot1 + it * 10 * minute, lateMinutes = 0) }
        assertEquals(Verdict.OK, verdict(r, Evidence.SEND))
        assertEquals(r, BackgroundHealth.withFingerprint(r, "old"))
        assertEquals(Verdict.PENDING, verdict(BackgroundHealth.withFingerprint(r, "new"), Evidence.SEND))

        r = BackgroundHealth.reset(r, Evidence.SEND, boot1)
        assertEquals(Verdict.PENDING, verdict(r, Evidence.SEND))
        assertEquals(Verdict.OK, verdict(r, Evidence.BREATH))
    }

    @Test
    fun resetRebootWaitsForTheNextReboot() {
        var r = check(HealthRecord(), boot1 + minute, boot1, tracking = true)
        r = check(r, boot2 + 30_000, boot2, tracking = true)
        r = check(r, boot2 + 30_000 + BackgroundHealth.BOOT_WAIT, boot2, tracking = true)
        assertEquals(Verdict.BAD, verdict(r, Evidence.REBOOT))
        r = BackgroundHealth.reset(r, Evidence.REBOOT, boot2)
        r = check(r, boot2 + 60 * minute, boot2, tracking = true)
        assertEquals(Verdict.PENDING, verdict(r, Evidence.REBOOT))
    }

    @Test
    fun lastFaultIsKeptAcrossSystemUpdates() {
        val r = BackgroundHealth.fault(HealthRecord(fingerprint = "a"), 5L, "rescue", "x".repeat(500))
        assertEquals(BackgroundHealth.FAULT_MAX, r.fault?.length, "说明截短，记录不无限长")
        val updated = BackgroundHealth.withFingerprint(r, "b")
        assertEquals(5L, updated.faultAt, "系统升级清掉厂商项证据，最近一次异常留着")
        assertEquals("rescue", updated.faultKind)
    }
}
