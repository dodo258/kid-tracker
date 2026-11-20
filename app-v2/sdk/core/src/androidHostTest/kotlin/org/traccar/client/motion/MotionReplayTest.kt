package org.traccar.client.motion

import org.traccar.client.sense.SnapshotCsv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 录制回放（方案第 5 节）：把 P20 的真实录制（脱敏后在 commonTest/resources/recordings/）按时间顺序喂给 [MotionClassifier]，
 * 对照人工标注的时间段检查判定，并把每段的判定统计打到标准输出（gradle 加 -i 能看到，报告里也有）。
 * 标注和判定规则写在 labels.csv 的开头。
 */
class MotionReplayTest {

    private class Label(val start: Long, val end: Long, val expect: String, val note: String)

    private fun resource(name: String): Sequence<String> {
        val stream = javaClass.classLoader?.getResourceAsStream("recordings/$name") ?: fail("缺测试资源 recordings/$name")
        return stream.bufferedReader().use { it.readLines() }.asSequence()
    }

    /** 11-17 夜到 11-18 中午：夜里静止、屋里走动、开车、走路 */
    @Test
    fun p20RecordingMatchesLabels() {
        replayMatchesLabels("p20-20261117-225048", 25_650)
    }

    /** 11-18 下午补测：桌上、热点、飞行模式、走到车旁、骑车（含等红灯） */
    @Test
    fun p20RideRecordingMatchesLabels() {
        val judged = replayMatchesLabels("p20-20261118-134404", 1_830)
        fun at(text: String) = judged.last { it.first <= time(text) }.second
        val firstWalk = judged.first { it.second == Motion.WALK }.first
        assertTrue(firstWalk in time("2026-11-18 14:30:44")..time("2026-11-18 14:31:00"), "14:30:52 前后判走路：${fmt(firstWalk)}")
        val firstRide = judged.first { it.first > firstWalk && it.second == Motion.VEHICLE }.first
        assertTrue(firstRide in time("2026-11-18 14:33:00")..time("2026-11-18 14:33:59"), "14:33 判坐车：${fmt(firstRide)}")
        assertEquals(Motion.VEHICLE, at("2026-11-18 14:39:30"), "等红灯")
    }

    /** 按时间顺序把一份录制喂给 [MotionClassifier]，对照标注；返回每份快照的判定 */
    private fun replayMatchesLabels(name: String, rows: Int): List<Pair<Long, Motion>> {
        val snapshots = SnapshotCsv.parse(resource("$name.csv"), BEIJING).toList()
        assertEquals(rows, snapshots.size, "录制行数")
        val classifier = MotionClassifier()
        // 录制脱敏时清了 BSSID、坐标：一直连着的不计费 Wi-Fi（家里的）当作上次卫星定位时连的那个，计费的（热点）不算
        val judged = snapshots.map { it.time to classifier.update(it, it.wifiBssid.takeIf { _ -> it.onFixedWifi }) }

        val labels = resource("$name.labels.csv")
            .filterNot { it.startsWith("#") || it.startsWith("start,") || it.isBlank() }
            .map { line ->
                val c = line.split(",", limit = 4)
                Label(time(c[0]), time(c[1]), c[2], c[3])
            }.toList()

        val failures = mutableListOf<String>()
        println("P20 录制回放 $name：${snapshots.size} 份快照")
        println("整段判定：" + share(judged.map { it.second }))
        for (label in labels) {
            val seg = judged.filter { it.first in label.start..label.end }
            val counts = share(seg.map { it.second })
            val firstHit = seg.firstOrNull { it.second.name == label.expect }?.first
            val delay = when {
                label.expect.contains('|') -> ""
                label.expect.contains(':') -> "VEHICLE 共 ${seg.count { it.second == Motion.VEHICLE } * SNAPSHOT_SECONDS / 60} 分钟"
                else -> firstHit?.let { "${(it - label.start) / 1000} 秒判出" } ?: "没判出"
            }
            println("${SnapshotCsv.formatTime(label.start, BEIJING).substring(5)}–${SnapshotCsv.formatTime(label.end, BEIJING).substring(11)} " +
                "应判 ${label.expect}：$counts；$delay（${label.note}）")
            val problem = check(label, seg.map { it.second }, firstHit)
            if (problem != null) failures += "${SnapshotCsv.formatTime(label.start, BEIJING)} ${label.expect}：$problem"
        }
        printLongVehicleRunsOutsideLabels(judged, labels)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        return judged
    }

    private fun fmt(t: Long) = SnapshotCsv.formatTime(t, BEIJING)

    /** 没标注的时段里连续 5 分钟以上判成坐车的：只打出来，等主人的活动记录核对（多是没 Wi-Fi、没卫星定位、一直轻晃的时候） */
    private fun printLongVehicleRunsOutsideLabels(judged: List<Pair<Long, Motion>>, labels: List<Label>) {
        val runs = mutableListOf<LongRange>()
        var start: Long? = null
        var last = 0L
        for ((t, m) in judged + (Long.MAX_VALUE to Motion.OFF)) {
            if (m == Motion.VEHICLE) {
                if (start == null) start = t
                last = t
            } else if (start != null) {
                runs += start..last
                start = null
            }
        }
        val labelled = labels.filter { it.expect.contains("VEHICLE") }
        val unlabelled = runs.filter { r -> r.last - r.first >= 5 * 60_000L && labelled.none { it.start <= r.last && r.first <= it.end } }
        println("没标注、连续 5 分钟以上判成 VEHICLE 的 ${unlabelled.size} 段，共 ${unlabelled.sumOf { it.last - it.first } / 60_000} 分钟（待核对）：")
        unlabelled.forEach {
            println("  ${SnapshotCsv.formatTime(it.first, BEIJING).substring(5)}–${SnapshotCsv.formatTime(it.last, BEIJING).substring(11)}，${(it.last - it.first) / 60_000} 分钟")
        }
    }

    private fun check(label: Label, seg: List<Motion>, firstHit: Long?): String? {
        if (seg.isEmpty()) return "段里没有快照"
        val ratio = { m: Motion -> seg.count { it == m }.toDouble() / seg.size }
        return when (label.expect) {
            "STILL" -> if (seg.all { it == Motion.STILL }) null else "有 ${seg.count { it != Motion.STILL }} 份不是 STILL"
            "OFF" -> if (seg.all { it == Motion.OFF }) null else "有 ${seg.count { it != Motion.OFF }} 份不是 OFF"
            "VEHICLE" -> if (ratio(Motion.VEHICLE) >= 0.9) null else "VEHICLE 只占 ${percent(ratio(Motion.VEHICLE))}"
            "FIDGET" -> seg.count { it == Motion.WALK || it == Motion.VEHICLE }.takeIf { it > 0 }?.let { "有 $it 份判成走路或坐车" }
            "VEHICLE|WALK" -> seg.count { it != Motion.VEHICLE && it != Motion.WALK }.takeIf { it > 0 }?.let { "有 $it 份不是坐车或走路" }
            "WALK" -> {
                val limit = label.start + minOf(MAX_WALK_DELAY_MILLIS, label.end - label.start)
                when {
                    firstHit == null || firstHit > limit -> "开始后 ${(limit - label.start) / 1000} 秒内没判出 WALK"
                    ratio(Motion.WALK) < 0.5 -> "WALK 只占 ${percent(ratio(Motion.WALK))}"
                    else -> null
                }
            }
            else -> {
                val range = minutes(label.expect) ?: return "标注里不认识的应判：${label.expect}"
                val got = seg.count { it == Motion.VEHICLE } * SNAPSHOT_SECONDS / 60
                if (got in range) null else "VEHICLE 共 $got 分钟，不在 ${range.first}–${range.last} 分钟"
            }
        }
    }

    /** 「VEHICLE:60-120」：段里判成 VEHICLE 的总分钟数范围 */
    private fun minutes(expect: String): IntRange? {
        val m = Regex("""VEHICLE:(\d+)-(\d+)""").matchEntire(expect) ?: return null
        return m.groupValues[1].toInt()..m.groupValues[2].toInt()
    }

    private fun share(motions: List<Motion>): String {
        val total = motions.size.coerceAtLeast(1)
        return motions.groupingBy { it }.eachCount().entries.sortedBy { it.key }
            .joinToString(" ") { "${it.key} ${percent(it.value.toDouble() / total)}" } + "（${motions.size} 份）"
    }

    private fun percent(ratio: Double) = "${(ratio * 100).toInt()}%"

    private fun time(text: String): Long = SnapshotCsv.parseTime(text, BEIJING) ?: fail("标注时间读不出来：$text")

    private companion object {
        const val BEIJING = 480
        const val MAX_WALK_DELAY_MILLIS = 60_000L
        const val SNAPSHOT_SECONDS = 2
    }
}
