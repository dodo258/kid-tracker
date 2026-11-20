package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.sense.Snapshot
import org.traccar.client.sense.SnapshotCsv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 判断层整体回放 P20 11-18 下午那份录制（桌上、热点、飞行模式、走到车旁、骑车、等红灯）：像引擎一样把快照、卫星点喂给 [Judge]，
 * 看什么时候开卫星、报了哪些点。录制里的卫星点是旧 App 开着高德时被动收到的，这里只在判断层要开卫星时才当成自己的点喂进去；
 * 网络定位当取不到（录制里没有坐标）。
 */
class JudgeReplayTest {

    private class Step(val time: Long, val gps: Boolean)

    private val reports = mutableListOf<Position>()
    private val steps = mutableListOf<Step>()
    private var fetches = 0

    private fun replay() {
        val stream = javaClass.classLoader?.getResourceAsStream("recordings/p20-20261118-134404.csv") ?: fail("缺录制")
        val snapshots = SnapshotCsv.parse(stream.bufferedReader().use { it.readLines() }.asSequence(), BEIJING).toList()
        val judge = Judge()
        var fedFix = Long.MIN_VALUE
        var now = 0L
        fun take(d: Decision?) {
            d ?: return
            d.report?.let { reports += judge.describe(it) }
            if (d.fetch) {
                fetches++
                take(judge.onFetched(now, null))
            }
        }
        for (s in snapshots) {
            now = s.time
            val fix = s.fix
            if (judge.gps && fix != null && fix.provider == "gps" && fix.time > fedFix) {
                fedFix = fix.time
                take(judge.onFix(s.time, Position(time = fix.time, accuracy = fix.accuracy, speed = fix.speed, provider = Provider.GPS)))
            }
            take(judge.onSnapshot(s.time, s.copy(fix = null)))
            steps += Step(s.time, judge.gps)
        }
    }

    @Test
    fun rideRecording() {
        replay()
        fun between(from: String, to: String) = time(from)..time(to)

        val airplane = between("2026-11-18 14:16:56", "2026-11-18 14:27:20")
        assertTrue(reports.none { it.time in airplane }, "飞行模式全停：${reports.filter { it.time in airplane }.map { fmt(it.time) }}")
        assertTrue(steps.filter { it.time in airplane }.none { it.gps })
        val exit = reports.first { it.time > airplane.last }
        assertTrue(exit.time - time("2026-11-18 14:27:22") <= 4_000, "退出飞行模式马上报：${fmt(exit.time)}")

        val walkStart = steps.first { it.time >= time("2026-11-18 14:30:40") && it.gps }.time
        assertTrue(walkStart <= time("2026-11-18 14:31:00"), "走路一判出就开卫星：${fmt(walkStart)}")

        val ride = between("2026-11-18 14:33:30", "2026-11-18 14:42:30")
        assertTrue(steps.filter { it.time in ride }.all { it.gps }, "骑车途中卫星一直开着（含 14:38:52 起等红灯 62 秒）")
        val rideReports = reports.filter { it.time in ride }
        // 点的时间是定位时间（不是报出的那一刻），所以间隔在 10 秒上下差一两秒
        val gaps = rideReports.zipWithNext { a, b -> b.time - a.time }
        assertTrue(gaps.all { it in 8_000..14_000 }, "骑车 10 秒左右一个点：${gaps.min()}–${gaps.max()}")
        // 14:33:24 后旧 App 的高德一分多钟没给新卫星点（录制里被动收到的就这些）：满 1 分钟报一个只带时间的点
        assertTrue(rideReports.all { it.state == "moving" && it.motion == "vehicle" && (it.provider == Provider.GPS || it.latitude == null) })
        assertTrue(rideReports.count { it.provider == Provider.GPS } >= 45)
        assertTrue(rideReports.all { it.net == "hotspot" }, "连着计费的 iPhone 热点")

        val home = reports.filter { it.time in between("2026-11-18 13:44:00", "2026-11-18 14:09:30") }
        assertTrue(home.isNotEmpty() && home.all { it.net == "wifi" && it.state != "moving" })
        assertEquals(listOf("zone", "still"), home.map { it.state }.distinct(), "桌上：先屋里、2 分钟后静止，只呼吸")
    }

    private fun time(text: String): Long = SnapshotCsv.parseTime(text, BEIJING) ?: fail(text)

    private fun fmt(t: Long) = SnapshotCsv.formatTime(t, BEIJING)

    private companion object {
        const val BEIJING = 480
    }
}
