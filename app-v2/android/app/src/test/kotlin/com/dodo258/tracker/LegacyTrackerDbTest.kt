package com.dodo258.tracker

import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 覆盖安装：旧 App（改版 SDK，库第 4–7 版）留下的 tracker.db 迁成 v2 SDK 的第 5 版（之后 SDK 自己往上迁）。
 * 用真 SQLite 造一个和旧 App 一样的库（建表语句抄自根目录 sdk/core 的 .sq/.sqm），迁移后跑原版 SDK 的查询。
 */
class LegacyTrackerDbTest {

    private lateinit var conn: Connection
    private lateinit var db: JdbcSql

    @Before
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        db = JdbcSql(conn)
    }

    @After
    fun close() = conn.close()

    // 旧 App 根目录 sdk/core 的最终表结构（1.sqm–6.sqm 跑完后），user_version = 7
    private fun createLegacyDb() {
        db.exec(
            """
            CREATE TABLE Position (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL, longitude REAL, accuracy REAL, time INTEGER NOT NULL,
                altitude REAL, speed REAL, bearing REAL, battery INTEGER, charging INTEGER,
                alarm TEXT, powerSave INTEGER, provider TEXT, state TEXT
            )
            """.trimIndent(),
        )
        db.exec("CREATE TABLE Config (id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1), json TEXT NOT NULL)")
        db.exec("CREATE TABLE State (id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1), json TEXT NOT NULL)")
        db.exec("CREATE TABLE LogEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, message TEXT NOT NULL)")
        db.version = 7
    }

    private fun insertLegacyPosition(lat: Double, time: Long, alarm: String?, provider: String? = "gps") = db.exec(
        "INSERT INTO Position(latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging, alarm, powerSave, provider, state) " +
            "VALUES (?, 116.4, 12.5, ?, 40.0, 1.5, 90.0, 87, 1, ?, 0, ?, 'MOVING')",
        listOf(lat, time, alarm, provider),
    )

    // 旧 App 10.2.23 存的配置：有原版没有的 minIntervalSeconds
    private val legacyConfig = """
        {"serverUrl":"https://gps.example.com/osmand","deviceId":"20000001",
         "location":{"accuracy":"HIGH","distanceMeters":50,"intervalSeconds":10,"stationaryRadiusMeters":200,
                     "heartbeatIntervalSeconds":300,"minIntervalSeconds":5},
         "buffer":true,"notification":{"text":"服务中"},"headers":{"X-Test":"1"}}
    """.trimIndent()

    // 旧 App 存的状态：三种状态、锚点、锚点 Wi-Fi，上一个点带 provider/state 等新字段
    private val legacyState = """
        {"enabled":true,"tracking":"ZONE","anchorWifi":"aa:bb:cc:dd:ee:ff",
         "zoneAnchor":{"latitude":39.9,"longitude":116.4,"accuracy":20.0,"time":1763400000000},
         "lastAcceptedLocation":{"latitude":39.9,"longitude":116.4,"time":1763400000000,"provider":"amap","state":"ZONE"}}
    """.trimIndent()

    @Test
    fun `旧库迁到 v2 第 5 版：离线点留下、多的列去掉，SDK 的读写都能用`() {
        createLegacyDb()
        insertLegacyPosition(39.1, 1000, null, provider = "gps")
        insertLegacyPosition(39.2, 2000, null, provider = "gps")
        db.exec("INSERT INTO Config(id, json) VALUES (1, ?)", listOf(legacyConfig))
        db.exec("INSERT INTO State(id, json) VALUES (1, ?)", listOf(legacyState))

        assertEquals(7, LegacyTrackerDb.migrate(db, now = 5000))

        assertEquals(5, db.version)
        assertEquals(
            listOf("id", "latitude", "longitude", "accuracy", "time", "altitude", "speed", "bearing", "battery", "charging", "extras"),
            columns("Position"),
        )
        // 原版 Position.sq 的 peek：最早的点原样
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging FROM Position ORDER BY id ASC LIMIT 1",
            ).use { r ->
                assertTrue(r.next())
                assertEquals(39.1, r.getDouble(1), 0.0)
                assertEquals(116.4, r.getDouble(2), 0.0)
                assertEquals(12.5, r.getDouble(3), 0.0)
                assertEquals(1000L, r.getLong(4))
                assertEquals(87, r.getInt(8))
                assertEquals(1, r.getInt(9))
            }
        }
        assertEquals("2", db.queryString("SELECT COUNT(*) FROM Position"))
        // 原版 Position.sq 的 enqueue、removeFirst
        db.exec(
            "INSERT INTO Position(latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(39.3, 116.5, 10.0, 3000L, null, null, null, 50, 0),
        )
        db.exec("DELETE FROM Position WHERE id = (SELECT MIN(id) FROM Position)")
        assertEquals("39.2", db.queryString("SELECT latitude FROM Position ORDER BY id ASC LIMIT 1"))
        assertEquals("3", db.queryString("SELECT MAX(id) FROM Position"))
        assertEquals(
            "Legacy tracker.db migrated from version 7 to 5",
            db.queryString("SELECT message FROM LogEntry WHERE time = 5000"),
        )
    }

    @Test
    fun `配置只留原版认识的字段，服务器、设备号、间隔不变`() {
        createLegacyDb()
        db.exec("INSERT INTO Config(id, json) VALUES (1, ?)", listOf(legacyConfig))
        db.exec("INSERT INTO State(id, json) VALUES (1, ?)", listOf(legacyState))

        LegacyTrackerDb.migrate(db)

        val config = JSONObject(db.queryString("SELECT json FROM Config WHERE id = 1")!!)
        assertEquals("https://gps.example.com/osmand", config.getString("serverUrl"))
        assertEquals("20000001", config.getString("deviceId"))
        assertEquals("服务中", config.getJSONObject("notification").getString("text"))
        assertEquals("1", config.getJSONObject("headers").getString("X-Test"))
        val location = config.getJSONObject("location")
        assertFalse(location.has("minIntervalSeconds"))
        assertEquals("HIGH", location.getString("accuracy"))
        assertEquals(50, location.getInt("distanceMeters"))
        assertEquals(10, location.getInt("intervalSeconds"))
        assertEquals(200, location.getInt("stationaryRadiusMeters"))
        assertEquals(300, location.getInt("heartbeatIntervalSeconds"))
        assertEquals(setOf("accuracy", "distanceMeters", "intervalSeconds", "stationaryRadiusMeters", "heartbeatIntervalSeconds"), location.keyNames())

        // 追踪开着的，装完接着追踪；别的状态让原版从头记
        val state = JSONObject(db.queryString("SELECT json FROM State WHERE id = 1")!!)
        assertEquals(setOf("enabled"), state.keyNames())
        assertTrue(state.getBoolean("enabled"))
    }

    @Test
    fun `追踪关着的，迁移后还是关着`() {
        assertEquals("""{"enabled":false}""", LegacyTrackerDb.state("""{"enabled":false,"tracking":"MOVING"}"""))
        assertEquals("""{"enabled":false}""", LegacyTrackerDb.state("""{"tracking":"STILL"}"""))
    }

    @Test
    fun `没存过配置和状态的旧库也能降`() {
        createLegacyDb()
        insertLegacyPosition(39.1, 1000, null)

        assertEquals(7, LegacyTrackerDb.migrate(db))

        assertEquals(5, db.version)
        assertNull(db.queryString("SELECT json FROM Config WHERE id = 1"))
        assertNull(db.queryString("SELECT json FROM State WHERE id = 1"))
        assertEquals("1", db.queryString("SELECT COUNT(*) FROM Position"))
    }

    @Test
    fun `已经是原版的库不动`() {
        db.exec("CREATE TABLE Position (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, extra TEXT)")
        db.exec("CREATE TABLE LogEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, message TEXT NOT NULL)")
        db.version = 3

        assertNull(LegacyTrackerDb.migrate(db))

        assertEquals(listOf("id", "time", "extra"), columns("Position"))
        assertEquals("0", db.queryString("SELECT COUNT(*) FROM LogEntry"))
    }

    @Test
    fun `v2 自己升到第 4 版的库不动`() {
        db.exec("CREATE TABLE Position (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, extras TEXT)")
        db.exec("CREATE TABLE LogEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, message TEXT NOT NULL)")
        db.version = 4

        assertNull(LegacyTrackerDb.migrate(db))

        assertEquals(listOf("id", "time", "extras"), columns("Position"))
        assertEquals(4, db.version)
    }

    @Test
    fun `v2 自己第 5 版带告警表的库不动`() {
        // R5 加了 Alert 表、库升到第 5 版：按版本号划界时会被当成旧库降回 3，App 一启动就报 table Alert already exists
        db.exec("CREATE TABLE Position (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, extras TEXT)")
        db.exec("CREATE TABLE Alert (id INTEGER PRIMARY KEY AUTOINCREMENT, alarm TEXT NOT NULL, time INTEGER NOT NULL, position TEXT NOT NULL)")
        db.exec("CREATE TABLE LogEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, message TEXT NOT NULL)")
        db.version = 5

        assertNull(LegacyTrackerDb.migrate(db))

        assertEquals(5, db.version)
        assertEquals(listOf("id", "time", "extras"), columns("Position"))
    }

    @Test
    fun `旧 App 第 4 到 6 版的库也降，离线点留着`() {
        // 旧 App 改版 SDK 的 3.sqm–5.sqm 依次加 alarm、powerSave、provider；第 7 版再加 state（上面的 createLegacyDb）
        val added = listOf("alarm TEXT", "powerSave INTEGER", "provider TEXT")
        for (version in 4..6) {
            conn.close()
            conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            db = JdbcSql(conn)
            db.exec(
                "CREATE TABLE Position (id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL, " +
                    "time INTEGER NOT NULL, altitude REAL, speed REAL, bearing REAL, battery INTEGER, charging INTEGER)",
            )
            added.take(version - 3).forEach { db.exec("ALTER TABLE Position ADD COLUMN $it") }
            db.exec("CREATE TABLE Config (id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1), json TEXT NOT NULL)")
            db.exec("CREATE TABLE State (id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1), json TEXT NOT NULL)")
            db.exec("CREATE TABLE LogEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, message TEXT NOT NULL)")
            db.exec("INSERT INTO Position(latitude, longitude, time, alarm) VALUES (39.1, 116.4, 1000, NULL)")
            db.version = version

            assertEquals("第 $version 版", version, LegacyTrackerDb.migrate(db))

            assertEquals(5, db.version)
            assertEquals(11, columns("Position").size)
            // 第 6 版起有来源列（这里是空，来源不明）；第 4、5 版没有：都只留时间
            assertNull(db.queryString("SELECT latitude FROM Position"))
            assertEquals("1000", db.queryString("SELECT time FROM Position"))
        }
    }

    @Test
    fun `中途失败整个回滚，库还是旧版本`() {
        createLegacyDb()
        insertLegacyPosition(39.1, 1000, null)
        db.exec("INSERT INTO Config(id, json) VALUES (1, ?)", listOf("不是 JSON"))

        try {
            LegacyTrackerDb.migrate(db)
            fail("坏的配置 JSON 应该抛异常")
        } catch (_: org.json.JSONException) {
        }

        assertEquals(7, db.version)
        assertEquals(14, columns("Position").size)
        assertNull(db.queryString("SELECT name FROM sqlite_master WHERE name = 'Position_v2'"))
        assertNull(db.queryString("SELECT name FROM sqlite_master WHERE name = 'Alert'"))
    }

    @Test
    fun `旧版没发出去的告警转进告警发件箱，类型不丢`() {
        // 审计高 2：以前只抄经纬度等通用列，待发告警丢了类型，变成一个普通点
        createLegacyDb()
        insertLegacyPosition(39.1, 1000, null)
        insertLegacyPosition(39.2, 2000, "sos", provider = "gps")
        insertLegacyPosition(39.3, 3000, "lowBattery", provider = "wifi")

        LegacyTrackerDb.migrate(db)

        assertEquals("1", db.queryString("SELECT COUNT(*) FROM Position"))
        val alerts = rows("SELECT alarm, time, position, ready, sentAt FROM Alert ORDER BY id")
        assertEquals(listOf("sos", "lowBattery"), alerts.map { it[0] })
        assertEquals(listOf(2000L, 3000L), alerts.map { (it[1] as Number).toLong() })
        assertTrue(alerts.all { (it[3] as Number).toInt() == 1 && it[4] == null })
        val sos = JSONObject(alerts[0][2] as String)
        assertEquals("sos", sos.getString("alarm"))
        assertEquals(2000L, sos.getLong("time"))
        assertEquals(39.2, sos.getDouble("latitude"), 0.0)
        assertEquals("gps", sos.getString("provider"))
        assertEquals(87, sos.getInt("battery"))
        assertTrue(sos.getBoolean("charging"))
        // Wi-Fi 定位的告警不带坐标（主坐标只放卫星点）
        val low = JSONObject(alerts[1][2] as String)
        assertFalse(low.has("latitude"))
        assertEquals("lowBattery", low.getString("alarm"))
    }

    @Test
    fun `旧版的网络定位点不当主坐标，估算放附加字段`() {
        createLegacyDb()
        insertLegacyPosition(39.1, 1000, null, provider = "gps")
        insertLegacyPosition(39.2, 2000, null, provider = "wifi")
        insertLegacyPosition(39.3, 3000, null, provider = null)

        LegacyTrackerDb.migrate(db)

        val rows = rows("SELECT latitude, time, extras FROM Position ORDER BY id")
        assertEquals(39.1, rows[0][0] as Double, 0.0)
        assertEquals("gps", JSONObject(rows[0][2] as String).getString("provider"))
        assertNull("Wi-Fi 点没有主坐标", rows[1][0])
        val net = JSONObject(rows[1][2] as String)
        assertEquals(39.2, net.getDouble("netLat"), 0.0)
        assertEquals(116.4, net.getDouble("netLon"), 0.0)
        assertEquals(12.5, net.getDouble("netAcc"), 0.0)
        assertEquals("wifi", net.getString("netSource"))
        assertNull("来源不明的只留时间", rows[2][0])
        assertNull(rows[2][2])
        assertEquals(3000L, (rows[2][1] as Number).toLong())
    }

    @Test
    fun `迁移失败时旧库改名留着，不删`() {
        // 审计高 1：以前迁移失败直接删库，没发出去的点和告警全丢
        val dir = java.nio.file.Files.createTempDirectory("legacy").toFile()
        try {
            val database = java.io.File(dir, "tracker.db").apply { writeText("old") }
            java.io.File(dir, "tracker.db-wal").writeText("wal")
            val backup = LegacyTrackerDb.setAside(database, now = 42)
            assertEquals(java.io.File(dir, "legacy-backup/tracker.db.42"), backup)
            assertFalse(database.exists())
            assertFalse(java.io.File(dir, "tracker.db-wal").exists())
            assertEquals("old", backup!!.readText())
            assertEquals("wal", java.io.File(dir, "legacy-backup/tracker.db.42-wal").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 真 SQLite 的 WAL 库：两条已提交的记录还在 -wal 里，没写回主库（连接开着、不自动检查点） */
    private fun walDatabase(dir: java.io.File): Pair<java.io.File, Connection> {
        val file = java.io.File(dir, "tracker.db")
        val c = DriverManager.getConnection("jdbc:sqlite:${file.path}")
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA wal_autocheckpoint=0")
            st.execute("CREATE TABLE Position (id INTEGER PRIMARY KEY, time INTEGER)")
            st.execute("INSERT INTO Position(time) VALUES (1), (2)")
        }
        assertTrue("记录在 -wal 里", java.io.File(dir, "tracker.db-wal").length() > 0)
        return file to c
    }

    private fun count(file: java.io.File): Int = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM Position").use { r -> r.next(); r.getInt(1) } }
    }

    @Test
    fun `日志改名失败时整组留在原地，已提交的记录不丢`() {
        // 审计 11.0.11 第 5 条：以前主库改了名、-wal 改名失败就删掉，备份显示成功却一条都读不出来
        val dir = java.nio.file.Files.createTempDirectory("legacy").toFile()
        try {
            val (database, open) = walDatabase(dir)
            open.use {
                val backup = LegacyTrackerDb.setAside(database, now = 42) { from, to ->
                    !from.name.endsWith("-wal") && from.renameTo(to)
                }
                assertNull("有一个搬不动就不算备份好", backup)
                assertTrue(database.exists())
                assertTrue(java.io.File(dir, "tracker.db-wal").exists())
                assertEquals("原库还能读出两条", 2, count(database))
                assertTrue("备份目录里不留半组", java.io.File(dir, "legacy-backup").listFiles().orEmpty().isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `主库改名失败时什么都不动`() {
        val dir = java.nio.file.Files.createTempDirectory("legacy").toFile()
        try {
            val database = java.io.File(dir, "tracker.db").apply { writeText("old") }
            java.io.File(dir, "tracker.db-wal").writeText("wal")
            assertNull(LegacyTrackerDb.setAside(database, now = 42) { _, _ -> false })
            assertEquals("old", database.readText())
            assertEquals("wal", java.io.File(dir, "tracker.db-wal").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `整组搬走后备份能读出已提交的记录`() {
        val dir = java.nio.file.Files.createTempDirectory("legacy").toFile()
        try {
            val (database, open) = walDatabase(dir)
            val backup = open.use { LegacyTrackerDb.setAside(database, now = 42) }
            assertEquals(java.io.File(dir, "legacy-backup/tracker.db.42"), backup)
            assertFalse(database.exists())
            assertEquals("备份连同 -wal 读得出两条", 2, count(backup!!))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun rows(sql: String): List<List<Any?>> = db.query(sql)

    // 编译时用的是 android.jar 里的 JSONObject，没有 keySet()
    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private fun columns(table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info($table)").use { r ->
            buildList { while (r.next()) add(r.getString("name")) }
        }
    }

    private class JdbcSql(private val conn: Connection) : LegacyTrackerDb.Sql {
        override var version: Int
            get() = queryString("PRAGMA user_version")!!.toInt()
            set(value) {
                conn.createStatement().use { it.execute("PRAGMA user_version = $value") }
            }

        override fun exec(sql: String, args: List<Any?>) {
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
                st.execute()
            }
        }

        override fun queryString(sql: String): String? = conn.createStatement().use { st ->
            st.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null }
        }

        override fun query(sql: String): List<List<Any?>> = conn.createStatement().use { st ->
            st.executeQuery(sql).use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            (1..r.metaData.columnCount).map { i ->
                                when (val v = r.getObject(i)) {
                                    is Int -> v.toLong()
                                    is Float -> v.toDouble()
                                    else -> v
                                }
                            },
                        )
                    }
                }
            }
        }

        override fun transaction(block: () -> Unit) {
            conn.autoCommit = false
            try {
                block()
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
