package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** 看门狗升级（方案「可靠性要求」第 4 条）：重建定位源 → 重建整套 → 报告警；告警一轮一次；安稳 30 分钟后从头来 */
class WatchdogTest {

    private val minute = 60_000L

    @Test
    fun escalatesLocationThenTrackerThenAlert() {
        val ladder = Ladder()
        assertEquals(Rescue.LOCATION, ladder.next(0))
        assertEquals(Rescue.TRACKER, ladder.next(15 * minute), "重建定位源后 30 分钟内又卡住：重建整套")
        assertEquals(Rescue.ALERT, ladder.next(25 * minute), "还卡住：报告警")
        assertEquals(Rescue.TRACKER, ladder.next(35 * minute), "告警一轮只报一次，之后一直重建整套")
        assertEquals(Rescue.TRACKER, ladder.next(40 * minute))
    }

    @Test
    fun calmPeriodStartsOverFromTheFirstLevel() {
        val ladder = Ladder()
        assertEquals(Rescue.LOCATION, ladder.next(0))
        assertEquals(Rescue.TRACKER, ladder.next(10 * minute))
        assertEquals(Rescue.ALERT, ladder.next(20 * minute))
        assertEquals(Rescue.LOCATION, ladder.next(50 * minute), "安稳 30 分钟：从第一级重新来")
        assertEquals(Rescue.TRACKER, ladder.next(55 * minute))
        assertEquals(Rescue.ALERT, ladder.next(60 * minute), "新的一轮可以再报一次告警")
    }
}
