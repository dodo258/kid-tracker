package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.motion.Motion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 方案第 3 节 policy/：按运动开关卫星、上报节奏。时间用秒写，喂给策略换成毫秒。 */
class ReportPolicyTest {

    private val policy = ReportPolicy()
    private val reports = mutableListOf<Outgoing>()
    private var fetches = 0

    private fun ms(seconds: Int) = seconds * 1000L
    private fun fix(seconds: Int, provider: String = Provider.GPS) =
        Position(latitude = 30.0, longitude = 120.0, accuracy = 8.0, time = ms(seconds), provider = provider)

    private fun take(d: Decision) {
        d.report?.let { reports += it }
        if (d.fetch) fetches++
    }

    private fun motion(at: Int, m: Motion) = take(policy.onMotion(ms(at), m))
    private fun tick(at: Int) = take(policy.onTick(ms(at)))
    private fun gpsEverySecond(from: Int, to: Int) {
        for (t in from until to) {
            take(policy.onFix(ms(t), fix(t)))
            tick(t)
        }
    }

    @Test
    fun walkingTurnsGpsOnAtOnceAndReportsEveryTenSeconds() {
        motion(0, Motion.FIDGET)
        assertFalse(policy.gps, "屋里走动不开卫星")
        reports.clear()
        motion(100, Motion.WALK)
        assertTrue(policy.gps, "一判出走路立刻开卫星，不等位置证明")
        assertEquals("state", reports.single().reason)
        assertNull(reports.single().fix, "刚开卫星还没有点：状态先报出去，只带时间")
        gpsEverySecond(101, 161)
        val moving = reports.drop(1)
        assertEquals(6, moving.size, "1 分钟 6 个点")
        assertTrue(moving.all { it.fix?.provider == Provider.GPS })
        assertEquals(listOf(110L, 120L, 130L, 140L, 150L, 160L).map { it * 1000 }, moving.map { it.time })
        // 走路换坐车不算状态变化，卫星一直开着
        motion(161, Motion.VEHICLE)
        assertTrue(policy.gps)
        assertEquals(7, reports.size)
    }

    @Test
    fun noGpsForThirtySecondsReportsNetworkPointsLabelled() {
        motion(0, Motion.WALK)
        reports.clear()
        for (t in 1..60) {
            take(policy.onFix(ms(t), fix(t, Provider.CELL)))
            tick(t)
        }
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.first().time >= ms(30), "卫星 30 秒没定到才先报网络点：${reports.first().time}")
        assertTrue(reports.all { it.fix?.provider == Provider.CELL })
        assertTrue(policy.gps, "卫星接着搜")
        // 卫星定到了就只报卫星点
        reports.clear()
        for (t in 61..80) {
            take(policy.onFix(ms(t), fix(t, Provider.CELL)))
            take(policy.onFix(ms(t), fix(t)))
            tick(t)
        }
        assertTrue(reports.all { it.fix?.provider == Provider.GPS })
    }

    @Test
    fun gpsLostAfterFixesFallsBackToNetworkAtOnce() {
        motion(0, Motion.WALK)
        gpsEverySecond(1, 41)
        reports.clear()
        // 走进楼里：卫星没了，只有网络点
        for (t in 41..80) {
            take(policy.onFix(ms(t), fix(t, Provider.WIFI)))
            tick(t)
        }
        assertTrue(reports.first().time <= ms(51), "最后一个卫星点过了 10 秒就拿网络点顶上：${reports.first().time}")
        assertTrue(reports.all { it.fix?.provider == Provider.WIFI })
    }

    @Test
    fun tunnelReportsTimeOnlyEveryMinuteThenGivesGpsARest() {
        motion(0, Motion.VEHICLE)
        gpsEverySecond(1, 31)
        reports.clear()
        for (t in 31..200) tick(t)
        assertEquals(listOf(90L, 150L).map { it * 1000 }, reports.map { it.time }, "隧道里什么点都没有：每分钟一个只带时间的点")
        assertTrue(reports.all { it.fix == null })
        assertTrue(policy.gps)
        // 最后一个卫星点（30 秒）后 3 分钟还搜不到：停，停着时每分钟取一次网络定位
        reports.clear()
        fetches = 0
        for (t in 201..209) tick(t)
        assertTrue(policy.gps)
        for (t in 210..300) tick(t)
        assertFalse(policy.gps, "最后一个卫星点后 3 分钟搜不到：停")
        assertEquals(1, fetches)
        take(policy.onFetched(ms(301), fix(301, Provider.CELL)))
        assertEquals("nogps", reports.single().reason)
        for (t in 302..330) tick(t)
        assertTrue(policy.gps, "停 2 分钟再试")
    }

    @Test
    fun fidgetFetchesANetworkPointEveryFiveMinutes() {
        motion(0, Motion.FIDGET)
        assertEquals(1, fetches, "转屋里走动：报状态、再取一次网络定位")
        assertNull(reports.single().fix)
        take(policy.onFetched(ms(3), fix(2, Provider.WIFI)))
        assertEquals(Provider.WIFI, reports.last().fix?.provider)
        for (t in 4..299) tick(t)
        assertEquals(1, fetches)
        tick(300)
        assertEquals(2, fetches, "5 分钟一次")
        take(policy.onFetched(ms(330), null))
        assertNull(reports.last().fix, "取不到就只报时间")
        assertEquals(3, reports.size)
        assertFalse(policy.gps)
    }

    @Test
    fun stillBreathesEveryFiveMinutesWithoutCoordinates() {
        motion(0, Motion.WALK)
        gpsEverySecond(1, 20)
        reports.clear()
        motion(20, Motion.STILL)
        assertFalse(policy.gps)
        val last = reports.single()
        assertNotNull(last.fix, "刚转静止时报最后位置：在动时的卫星点还新")
        assertEquals(0, fetches)
        for (t in 21..1000) tick(t)
        assertEquals(listOf(320L, 620L, 920L).map { it * 1000 }, reports.drop(1).map { it.time })
        assertTrue(reports.drop(1).all { it.fix == null && it.reason == "breath" })
    }

    @Test
    fun stillAfterMovingWithoutAFreshFixFetchesTheLastPosition() {
        motion(0, Motion.WALK)
        reports.clear()
        motion(200, Motion.STILL)
        assertEquals(1, fetches, "走着走着停下、卫星早没点了：取一次网络定位当最后位置")
        take(policy.onFetched(ms(205), fix(204, Provider.WIFI)))
        assertEquals(listOf("state", "last"), reports.map { it.reason })
        // 没取到就不补
        motion(300, Motion.WALK)
        reports.clear()
        motion(400, Motion.STILL)
        take(policy.onFetched(ms(401), null))
        assertEquals(listOf("state"), reports.map { it.reason })
    }

    @Test
    fun zoneAndStillSwitchWithoutFetchingAgain() {
        motion(0, Motion.FIDGET)
        take(policy.onFetched(ms(1), fix(1, Provider.WIFI)))
        reports.clear()
        fetches = 0
        motion(100, Motion.STILL)
        motion(400, Motion.FIDGET)
        assertEquals(0, fetches, "人没换地方：只报状态，不再取网络定位（两次的误差会让圆点原地抖）")
        assertEquals(listOf("state", "state"), reports.map { it.reason })
        assertTrue(reports.all { it.fix == null })
        for (t in 401..699) tick(t)
        assertEquals(0, fetches)
        tick(700)
        assertEquals(1, fetches, "屋里的下一次照常 5 分钟后")
    }

    @Test
    fun airplaneModeStopsEverythingAndReportsOnExit() {
        motion(0, Motion.WALK)
        gpsEverySecond(1, 20)
        reports.clear()
        fetches = 0
        motion(20, Motion.OFF)
        assertFalse(policy.gps)
        assertNull(policy.state)
        for (t in 21..1000) {
            take(policy.onFix(ms(t), fix(t)))
            tick(t)
        }
        assertTrue(reports.isEmpty() && fetches == 0, "飞行模式全停")
        motion(1001, Motion.FIDGET)
        assertEquals("state", reports.single().reason, "退出时马上报一次")
        assertEquals(ReportState.ZONE, policy.state)
    }

    @Test
    fun staleFetchResultForAnOldStateIsNotReported() {
        motion(0, Motion.FIDGET)
        reports.clear()
        motion(10, Motion.WALK)
        take(policy.onFetched(ms(12), fix(11, Provider.WIFI)))
        assertEquals(listOf("state"), reports.map { it.reason }, "屋里时取的点回来时已经在走路：不当屋里的点报")
    }

    @Test
    fun aFixIsNeverReportedTwice() {
        motion(0, Motion.WALK)
        take(policy.onFix(ms(1), fix(1)))
        for (t in 2..30) tick(t)
        assertEquals(1, reports.count { it.fix != null })
    }

    @Test
    fun clockSetBackKeepsBreathingEveryFiveMinutes() {
        motion(0, Motion.STILL)
        reports.clear()
        tick(300)
        assertEquals(1, reports.size, "5 分钟呼吸一次")
        // 有人把手机时间往回拨了一天：照样呼吸，不等时间追上原来那个点（撤掉往回挪就一天不报）。
        // 量不出中间过了多久时，当作拨回去那一刻就是上次调用的时刻（这里晚了 10 秒）
        val back = 300 - 86_400
        tick(back + 10)
        tick(back + 309)
        assertEquals(1, reports.size, "间隔没到不报")
        tick(back + 310)
        assertEquals(2, reports.size, "往回拨后仍按 5 分钟呼吸")
        assertEquals(ms(back + 310), reports.last().time)
    }

    @Test
    fun knownClockShiftKeepsTheExactCadence() {
        motion(0, Motion.STILL)
        reports.clear()
        tick(300)
        // 引擎拿开机时长比出时间被改了多少：一点不差地挪过去
        policy.shift(-ms(86_400))
        tick(600 - 86_400 - 1)
        assertEquals(1, reports.size)
        tick(600 - 86_400)
        assertEquals(2, reports.size, "往回拨一天，下一次呼吸还是 5 分钟后")
        policy.shift(ms(3_600))
        tick(900 - 86_400 + 3_600 - 1)
        assertEquals(2, reports.size, "往前拨也不提早报")
        tick(900 - 86_400 + 3_600)
        assertEquals(3, reports.size)
    }

    @Test
    fun clockSetBackWhileMovingKeepsReportingEveryTenSeconds() {
        motion(0, Motion.WALK)
        gpsEverySecond(1, 31)
        val before = reports.size
        // 往回拨 2 小时：卫星点的时间是卫星给的、不跟着变，网络和系统时间都往回走
        for (t in 31 until 71) {
            val now = t - 7_200
            take(policy.onFix(ms(now), fix(t)))
            tick(now)
        }
        assertEquals(3, reports.size - before, "往回拨后仍 10 秒一个卫星点")
    }
}
