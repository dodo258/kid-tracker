package org.traccar.client.policy

import org.traccar.client.Position
import org.traccar.client.motion.Motion
import org.traccar.client.sense.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 模拟器（tools/simulator）的手机用的是判断层的 Python 版（reporter.py）。这里把它记下的金样本（commonTest/resources/simulator/，
 * `python3 tools/simulator/sim.py golden` 生成）里的输入原样喂给 Kotlin 的 [Judge]，逐行比对判断：每份快照之后的运动判断和开不开卫星、
 * 报了什么、什么时候取网络定位、丢了哪个 Wi-Fi 定位。对不上就是两边规则不一样了，改哪边都要两边一起改。
 *
 * 输入行：S 快照、F 连续定位来的点、R 取网络定位的结果、X 进程重启（Wi-Fi 记忆、上一个主坐标存在状态库里，接着用；第三列是接着的运动状态）；
 * 判断行：m、r、f、d（格式见 tools/simulator/phone.py 的 golden_*）。
 */
class SimulatorGoldenTest {

    private val names = listOf(
        "airplane-20min", "arrive-wifi-wrong", "bus-lights-tunnel", "car-wifi-wait", "fresh-install-indoor", "gps-cold-start", "home-net-only",
        "process-killed", "still-after-update", "walk-school-jumps",
    )

    @Test
    fun kotlinJudgeMatchesTheSimulator() {
        for (name in names) replay(name)
    }

    private fun replay(name: String) {
        val lines = javaClass.classLoader?.getResourceAsStream("simulator/$name.golden")?.bufferedReader()?.use { it.readLines() }
            ?: fail("缺金样本 simulator/$name.golden")
        val expected = lines.filter { it.isNotEmpty() && it[0].isLowerCase() }
        val got = mutableListOf<String>()
        var judge = Judge()

        fun take(d: Decision?, now: Long) {
            if (d == null) {
                got += "d;$now"
                return
            }
            d.report?.let { out ->
                val p = judge.describe(out)
                val fixTime = out.fix?.time?.toString() ?: "-"
                got += "r;${out.time};${out.reason};${p.state.orEmpty()};${p.motion.orEmpty()};${p.provider ?: "-"};$fixTime;" +
                    "${p.steps ?: "-"};${p.bumps};${p.net};${p.netSource ?: "-"};${p.wifiSame ?: "-"}"
            }
            if (d.fetch) got += "f;$now"
        }

        for (line in lines) {
            if (line.isEmpty() || !line[0].isUpperCase()) continue
            val c = line.split(";")
            val now = c[1].toLong()
            when (c[0]) {
                "S" -> {
                    take(judge.onSnapshot(now, snapshot(c)), now)
                    got += "m;$now;${judge.motion?.wire};${if (judge.gps) 1 else 0}"
                }
                "F" -> take(judge.onFix(now, fix(c) ?: fail("F 行没有点：$line")), now)
                "R" -> take(judge.onFetched(now, fix(c)), now)
                "X" -> judge = Judge(judge.wifiMemory, restored = c.getOrNull(2)?.let { Motion.valueOf(it.uppercase()) }, main = judge.mainFix)
                else -> fail("不认识的金样本行：$line")
            }
        }
        val first = expected.indices.firstOrNull { it >= got.size || got[it] != expected[it] }
        if (first != null || got.size != expected.size) {
            val i = first ?: expected.size
            fail("$name 第 ${i + 1} 条判断不一样：模拟器 ${expected.getOrNull(i)}，Kotlin ${got.getOrNull(i)}（共 ${expected.size} / ${got.size} 条）")
        }
        assertEquals(expected, got)
    }

    private fun snapshot(c: List<String>) = Snapshot(
        time = c[1].toLong(),
        airplane = c[2] == "1",
        accMean = c[3].toDoubleOrNull(),
        accStd = c[4].toDoubleOrNull(),
        accN = c[5].toInt(),
        accPeaks = c[6].toIntOrNull(),
        gyroMean = c[7].toDoubleOrNull(),
        stepsTotal = c[8].toLongOrNull(),
        net = Snapshot.Net.fromCsv(c[9]),
        wifiBssid = c[10],
        wifiSsid = c[11],
        wifiRssi = c[12].toIntOrNull(),
        wifiMetered = when (c[13]) {
            "1" -> true
            "0" -> false
            else -> null
        },
        wifiGateway = c[14],
    )

    private fun fix(c: List<String>): Position? {
        if (c[2] == "-") return null
        return Position(
            time = c[3].toLong(),
            latitude = c[4].toDoubleOrNull(),
            longitude = c[5].toDoubleOrNull(),
            accuracy = c[6].toDoubleOrNull(),
            speed = c[7].toDoubleOrNull(),
            provider = c[2],
        )
    }
}
