package org.traccar.client.sense

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * 快照和录制 CSV 互转：App 的「传感器录制（测试用）」写，回放单测和以后的模拟器读。
 * 时间列是本地时间 `yyyy-MM-dd HH:mm:ss`（和 2026-11-17 起 P20 录的文件一样），换算要给 UTC 偏移（分钟，北京时间 480）。
 * 按表头的列名读，少列（旧文件没有 acc_peaks、卫星和磁场列，模拟器不写某列）就当没有；新列只往末尾加。
 */
object SnapshotCsv {

    val COLUMNS = listOf(
        "time", "screen", "charging", "battery", "airplane",
        "acc_mean", "acc_std", "acc_max", "acc_n", "gyro_mean", "gyro_max", "heading", "heading_n",
        "steps_total", "step_events", "proximity", "light", "pressure",
        "net", "wifi_bssid", "wifi_ssid", "wifi_rssi", "wifi_metered", "scan_n", "scan_overlap",
        "fix_provider", "fix_time", "fix_lat", "fix_lon", "fix_acc", "fix_speed", "fix_bearing",
        "acc_peaks", "wifi_gateway", "cell_tac", "cell_id",
        "sat_used", "cn0_top4", "mag_std", "cell_dbm",
        "net_link", "elapsed", "fix_elapsed",
    )

    val HEADER = COLUMNS.joinToString(",")

    fun format(s: Snapshot, utcOffsetMinutes: Int): String {
        val fix = s.fix
        return listOf(
            formatTime(s.time, utcOffsetMinutes), flag(s.screen), flag(s.charging), s.battery?.toString().orEmpty(), flag(s.airplane),
            num(s.accMean, 3), num(s.accStd, 3), num(s.accMax, 3), s.accN.toString(),
            num(s.gyroMean, 3), num(s.gyroMax, 3), num(s.heading, 0), s.headingN.toString(),
            s.stepsTotal?.toString().orEmpty(), s.stepEvents.toString(),
            num(s.proximity, 1), num(s.light, 0), num(s.pressure, 2),
            s.net.csv, quote(s.wifiBssid), quote(s.wifiSsid), s.wifiRssi?.toString().orEmpty(), flag(s.wifiMetered),
            s.scanN?.toString().orEmpty(), num(s.scanOverlap, 2),
            quote(fix?.provider.orEmpty()), fix?.let { formatTime(it.time, utcOffsetMinutes) }.orEmpty(),
            num(fix?.latitude, 6), num(fix?.longitude, 6), num(fix?.accuracy, 0), num(fix?.speed, 1), num(fix?.bearing, 0),
            s.accPeaks?.toString().orEmpty(), quote(s.wifiGateway),
            s.cellTac?.toString().orEmpty(), s.cellId?.toString().orEmpty(),
            s.satUsed?.toString().orEmpty(), num(s.cn0Top4, 1), num(s.magStd, 3), s.cellDbm?.toString().orEmpty(),
            s.netLink?.toString().orEmpty(), s.elapsed?.toString().orEmpty(), fix?.elapsed?.toString().orEmpty(),
        ).joinToString(",")
    }

    /** 读一个文件：第一行是表头。时间读不出来的行跳过（录制被杀时最后一行可能只写了一半）。 */
    fun parse(lines: Sequence<String>, utcOffsetMinutes: Int): Sequence<Snapshot> = sequence {
        var index: Map<String, Int>? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val cells = split(line)
            val columns = index
            if (columns == null) {
                index = cells.withIndex().associate { it.value.trim() to it.index }
                continue
            }
            parseRow(cells, columns, utcOffsetMinutes)?.let { yield(it) }
        }
    }

    private fun parseRow(cells: List<String>, index: Map<String, Int>, offset: Int): Snapshot? {
        fun cell(name: String): String = index[name]?.let { cells.getOrNull(it) }.orEmpty()
        fun double(name: String) = cell(name).toDoubleOrNull()?.takeIf { it.isFinite() }
        fun int(name: String) = cell(name).toIntOrNull()
        fun bool(name: String) = when (cell(name)) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val time = parseTime(cell("time"), offset) ?: return null
        val provider = cell("fix_provider")
        val fixTime = parseTime(cell("fix_time"), offset)
        return Snapshot(
            time = time,
            screen = bool("screen"),
            charging = bool("charging"),
            battery = int("battery"),
            airplane = bool("airplane"),
            accMean = double("acc_mean"),
            accStd = double("acc_std"),
            accMax = double("acc_max"),
            accN = int("acc_n") ?: 0,
            accPeaks = int("acc_peaks"),
            gyroMean = double("gyro_mean"),
            gyroMax = double("gyro_max"),
            heading = double("heading"),
            headingN = int("heading_n") ?: 0,
            stepsTotal = double("steps_total")?.toLong(),
            stepEvents = int("step_events") ?: 0,
            proximity = double("proximity"),
            light = double("light"),
            pressure = double("pressure"),
            net = Snapshot.Net.fromCsv(cell("net")),
            wifiBssid = cell("wifi_bssid"),
            wifiSsid = cell("wifi_ssid"),
            wifiRssi = int("wifi_rssi"),
            wifiMetered = bool("wifi_metered"),
            wifiGateway = cell("wifi_gateway"),
            scanN = int("scan_n"),
            scanOverlap = double("scan_overlap"),
            fix = if (provider.isEmpty() || fixTime == null) null else Snapshot.Fix(
                provider = provider,
                time = fixTime,
                latitude = double("fix_lat"),
                longitude = double("fix_lon"),
                accuracy = double("fix_acc"),
                speed = double("fix_speed"),
                bearing = double("fix_bearing"),
                elapsed = cell("fix_elapsed").toLongOrNull(),
            ),
            cellTac = int("cell_tac"),
            cellId = cell("cell_id").toLongOrNull(),
            satUsed = int("sat_used"),
            cn0Top4 = double("cn0_top4"),
            magStd = double("mag_std"),
            cellDbm = int("cell_dbm"),
            netLink = int("net_link"),
            elapsed = cell("elapsed").toLongOrNull(),
        )
    }

    /** 按逗号拆一行，认双引号（Wi-Fi 名字里可能有逗号、引号） */
    internal fun split(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { cells.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun flag(b: Boolean?) = when (b) {
        true -> "1"
        false -> "0"
        null -> ""
    }

    /** 固定小数位，四舍五入；null、NaN 写空 */
    internal fun num(d: Double?, digits: Int): String {
        if (d == null || !d.isFinite()) return ""
        val scaled = round(abs(d) * 10.0.pow(digits)).toLong()
        val sign = if (d < 0 && scaled != 0L) "-" else ""
        if (digits == 0) return sign + scaled
        val text = scaled.toString().padStart(digits + 1, '0')
        return sign + text.dropLast(digits) + "." + text.takeLast(digits)
    }

    // 本地时间 yyyy-MM-dd HH:mm:ss ↔ Unix 毫秒。日期换算用公历「距 1970-01-01 的天数」算法（H. Hinnant，days_from_civil）。

    internal fun formatTime(millis: Long, utcOffsetMinutes: Int): String {
        val local = millis.floorDiv(1000L) + utcOffsetMinutes * 60L
        val days = local.floorDiv(86_400L)
        val second = local.mod(86_400L)
        val (y, m, d) = civilFromDays(days)
        return "$y-${two(m)}-${two(d)} ${two((second / 3600).toInt())}:${two((second / 60 % 60).toInt())}:${two((second % 60).toInt())}"
    }

    internal fun parseTime(text: String, utcOffsetMinutes: Int): Long? {
        val match = TIME.matchEntire(text.trim()) ?: return null
        val (y, mo, d, h, mi, s) = match.destructured
        val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
        val local = days * 86_400L + h.toInt() * 3600L + mi.toInt() * 60L + s.toInt()
        return (local - utcOffsetMinutes * 60L) * 1000L
    }

    private val TIME = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})""")

    private fun two(v: Int) = v.toString().padStart(2, '0')

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }
}
