package com.dodo258.tracker

import java.io.File
import org.json.JSONObject

/**
 * 覆盖安装根目录旧 App 后，把它留下的 tracker.db 原地改成 v2 SDK 第 [TARGET_SCHEMA_VERSION] 版的样子（之后的版本 SDK 打开时自己往上迁）。
 *
 * 旧 App 的改版 SDK 把库升到了第 7 版：Position 多了 alarm、powerSave、provider、state 四列，
 * Config、State 两张表里的 JSON 有原版不认识的字段（minIntervalSeconds、tracking、zoneAnchor……）。
 * 原版 SDK 直接打开会报「不能从 7 降到 3」，读到多出来的字段也会抛异常，两种都会让 App 一启动就崩。
 *
 * 改法：
 * - 离线队列里的普通点保留，按来源放（v2 主坐标只放卫星点，方案 3a）：卫星点留主坐标；Wi-Fi、基站、网络定位挪进 extras 的
 *   netLat / netLon / netAcc / netSource；来源不明的只留时间。
 * - 带 alarm 的行（旧版没发出去的告警）转进 Alert 表（v2 的告警发件箱，4.sqm 建的），告警类型不丢（审计高 2）。
 * - 配置只留原版有的字段，服务器、设备号、间隔等原样；状态只留「是否在追踪」，覆盖安装前开着追踪的，装完接着追踪。
 */
object LegacyTrackerDb {

    /**
     * 迁到 v2 SDK 的第 5 版：1.sqm–4.sqm 跑完的样子（Position 带 extras 列、有 Alert 表）。以后 SDK 再加 5.sqm、6.sqm……
     * 这里不用跟着改，SDK 从 5 往上迁。
     */
    const val TARGET_SCHEMA_VERSION = 5

    /**
     * 认旧 App 的库看 Position 表有没有 alarm 列：旧 App 的改版 SDK 从第 4 版起就有（3.sqm 加的），v2 的 SDK 永远没有
     * （告警存 Alert 表）。不比版本号：v2 的库版本会往上涨（R3 到 4、R5 到 5），按数字划界每加一张表都得跟着挪，
     * 挪漏了 v2 自己的新库会被当成旧库降回 3、再往上迁时重建已有的表，App 一启动就崩（R5 在模拟器上踩到过）。
     */
    fun isLegacy(db: Sql): Boolean {
        val create = db.queryString("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'Position'") ?: return false
        return LEGACY_COLUMN.containsMatchIn(create)
    }

    private val LEGACY_COLUMN = Regex("""\balarm\b""", RegexOption.IGNORE_CASE)

    /** 迁移只用到这几样，安卓上包 SQLiteDatabase，JVM 测试里包 sqlite-jdbc */
    interface Sql {
        var version: Int
        fun exec(sql: String, args: List<Any?> = emptyList())
        fun queryString(sql: String): String?

        /** 每行按列给值：整数是 Long、小数是 Double、文字是 String、空是 null */
        fun query(sql: String): List<List<Any?>>
        fun transaction(block: () -> Unit)
    }

    private val CONFIG_KEYS = setOf(
        "serverUrl", "deviceId", "location", "wakeLock", "buffer", "preferPlatformProviders", "notification", "headers",
    )
    private val LOCATION_KEYS = setOf(
        "accuracy", "distanceMeters", "intervalSeconds", "angleDegrees",
        "stopDetection", "stopTimeoutSeconds", "stationaryRadiusMeters", "heartbeatIntervalSeconds",
    )
    private val NOTIFICATION_KEYS = setOf("text")

    /** 是旧 App 的库（[isLegacy]）才动，返回旧版本号；不用动时返回 null。整个过程在一个事务里，失败时库保持原样。 */
    fun migrate(db: Sql, now: Long = System.currentTimeMillis()): Int? {
        if (!isLegacy(db)) return null
        val from = db.version
        db.transaction {
            db.exec(
                """
                CREATE TABLE Position_v2 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    latitude REAL,
                    longitude REAL,
                    accuracy REAL,
                    time INTEGER NOT NULL,
                    altitude REAL,
                    speed REAL,
                    bearing REAL,
                    battery INTEGER,
                    charging INTEGER,
                    extras TEXT
                )
                """.trimIndent(),
            )
            // 旧 App 第 6 版起才有 provider 列；没有的当来源不明
            val create = db.queryString("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'Position'").orEmpty()
            val provider = if (PROVIDER_COLUMN.containsMatchIn(create)) "provider" else "NULL"
            // 卫星点原样（extras 记来源）
            db.exec(
                "INSERT INTO Position_v2(id, latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging, extras) " +
                    "SELECT id, latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging, ? " +
                    "FROM Position WHERE alarm IS NULL AND $provider = 'gps'",
                listOf(JSONObject().put("provider", "gps").toString()),
            )
            // 网络定位：主坐标空着，估算放 extras
            val network = db.query(
                "SELECT id, time, battery, charging, latitude, longitude, accuracy, $provider FROM Position " +
                    "WHERE alarm IS NULL AND $provider IN ('wifi', 'cell', 'network') AND latitude IS NOT NULL AND longitude IS NOT NULL",
            )
            for (row in network) {
                val extras = JSONObject().put("netLat", row[4]).put("netLon", row[5]).put("netAcc", row[6]).put("netSource", row[7])
                db.exec(
                    "INSERT INTO Position_v2(id, time, battery, charging, extras) VALUES (?, ?, ?, ?, ?)",
                    listOf(row[0], row[1], row[2], row[3], extras.toString()),
                )
            }
            // 来源不明（或网络定位没坐标）：只留时间
            db.exec(
                "INSERT INTO Position_v2(id, time, battery, charging) " +
                    "SELECT id, time, battery, charging FROM Position WHERE alarm IS NULL AND id NOT IN (SELECT id FROM Position_v2)",
            )
            // 4.sqm 的告警发件箱；旧版没发出去的告警转进来，位置按同样的规则只带卫星点
            db.exec(
                """
                CREATE TABLE Alert (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alarm TEXT NOT NULL,
                    time INTEGER NOT NULL,
                    position TEXT NOT NULL,
                    ready INTEGER NOT NULL DEFAULT 1,
                    sentAt INTEGER
                )
                """.trimIndent(),
            )
            val alarms = db.query(
                "SELECT alarm, time, battery, charging, latitude, longitude, accuracy, $provider FROM Position WHERE alarm IS NOT NULL ORDER BY id",
            )
            for (row in alarms) {
                db.exec(
                    "INSERT INTO Alert(alarm, time, position, ready) VALUES (?, ?, ?, 1)",
                    listOf(row[0], row[1], alertPosition(row).toString()),
                )
            }
            db.exec("DROP TABLE Position")
            db.exec("ALTER TABLE Position_v2 RENAME TO Position")
            db.queryString("SELECT json FROM Config WHERE id = 1")?.let {
                db.exec("UPDATE Config SET json = ? WHERE id = 1", listOf(config(it)))
            }
            db.queryString("SELECT json FROM State WHERE id = 1")?.let {
                db.exec("UPDATE State SET json = ? WHERE id = 1", listOf(state(it)))
            }
            // 写进 SDK 自己的日志表，App 的日志页看得到
            db.exec(
                "INSERT INTO LogEntry(time, message) VALUES (?, ?)",
                listOf(now, "Legacy tracker.db migrated from version $from to $TARGET_SCHEMA_VERSION"),
            )
            db.version = TARGET_SCHEMA_VERSION
        }
        return from
    }

    /**
     * 迁移失败（存储满了、库坏了）时把旧库连同 -journal、-wal、-shm 改名放一边（审计高 1：以前直接删，没发出去的轨迹和告警全丢）。
     * 不动的话原版 SDK 打开旧库就崩；改名后 SDK 建新库，旧库留着能手动救回。放在同目录的 legacy-backup/ 里（备份规则整个排除，
     * 不进云备份）。返回备份的路径。
     *
     * 主库和这几个日志文件是一组：已提交的记录可能还在 -wal 里没写回主库，只搬走主库、删掉 -wal 就丢了（审计 11.0.11 第 5 条）。
     * 任何一个搬不动，已经搬走的都搬回原处、返回 null，原文件一个不少留在原地，下次启动再迁移；调用方不删。
     */
    fun setAside(database: File, now: Long = System.currentTimeMillis(), rename: (File, File) -> Boolean = File::renameTo): File? {
        val dir = File(database.parentFile, "legacy-backup")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val backup = File(dir, "${database.name}.$now")
        val group = listOf("" to database) + SIDE_FILES.map { it to File(database.path + it) }.filter { it.second.exists() }
        val moved = mutableListOf<Pair<File, File>>()
        for ((suffix, file) in group) {
            val target = File(backup.path + suffix)
            if (!rename(file, target)) {
                // 已经搬走的倒着搬回原处
                for ((from, to) in moved.asReversed()) rename(to, from)
                return null
            }
            moved += file to target
        }
        return backup
    }

    private val SIDE_FILES = listOf("-journal", "-wal", "-shm")

    /** 告警要报的那一条（v2 Position 的 JSON，字段名一样）：卫星点才带坐标 */
    private fun alertPosition(row: List<Any?>): JSONObject {
        val json = JSONObject().put("time", row[1]).put("alarm", row[0]).put("battery", row[2])
        (row[3] as? Number)?.let { json.put("charging", it.toLong() != 0L) }
        if (row[7] == "gps" && row[4] != null && row[5] != null) {
            json.put("latitude", row[4]).put("longitude", row[5]).put("accuracy", row[6]).put("provider", "gps")
        }
        return json
    }

    private val PROVIDER_COLUMN = Regex("""\bprovider\b""", RegexOption.IGNORE_CASE)

    /** 旧配置只留原版 Config 有的字段，值不变 */
    fun config(json: String): String {
        val old = JSONObject(json)
        val result = keep(old, CONFIG_KEYS)
        old.optJSONObject("location")?.let { result.put("location", keep(it, LOCATION_KEYS)) }
        old.optJSONObject("notification")?.let { result.put("notification", keep(it, NOTIFICATION_KEYS)) }
        return result.toString()
    }

    /** 旧状态只留「是否在追踪」；其余（锚点、上一个点等）原版要么没有、要么格式不同，让原版从头记 */
    fun state(json: String): String =
        JSONObject().put("enabled", JSONObject(json).optBoolean("enabled", false)).toString()

    private fun keep(source: JSONObject, keys: Set<String>): JSONObject {
        val result = JSONObject()
        for (key in keys) {
            if (source.has(key)) result.put(key, source.get(key))
        }
        return result
    }
}
