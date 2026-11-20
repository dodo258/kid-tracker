package org.traccar.client

/**
 * 把老位置的库（附加成 `ce`）并进现在用的库（`main`）：没解锁也要恢复上报（Direct Boot）以后，SDK 的库放在设备保护存储，
 * 之前的版本放在凭据存储（解锁后才能读）。两边都有库时（升级时没搬成、没解锁时已经建了新库）逐行并过来：
 * - 离线点、告警、日志：`main` 里已经有一模一样的（同一时间、同样内容）就不再插，重复跑也不多出一条（不丢不重）；
 * - 配置、追踪开关等状态：`main` 里有就用 `main` 的（那是更新的），没有才用老库的。
 * 两边都要先升到同一个库版本（Android 侧先用 SQLDelight 打开一次老库）。
 */
object StorageMerge {
    const val ALIAS = "ce"

    val statements: List<String> = listOf(
        """
        INSERT INTO main.Position(latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging, extras)
        SELECT latitude, longitude, accuracy, time, altitude, speed, bearing, battery, charging, extras
        FROM $ALIAS.Position c
        WHERE NOT EXISTS (
            SELECT 1 FROM main.Position m
            WHERE m.time = c.time AND m.latitude IS c.latitude AND m.longitude IS c.longitude AND m.extras IS c.extras
        )
        ORDER BY c.id
        """,
        """
        INSERT INTO main.Alert(alarm, time, position, ready, sentAt)
        SELECT alarm, time, position, ready, sentAt
        FROM $ALIAS.Alert c
        WHERE NOT EXISTS (
            SELECT 1 FROM main.Alert m WHERE m.alarm = c.alarm AND m.time = c.time AND m.position = c.position
        )
        ORDER BY c.id
        """,
        """
        INSERT INTO main.LogEntry(time, message)
        SELECT time, message
        FROM $ALIAS.LogEntry c
        WHERE NOT EXISTS (SELECT 1 FROM main.LogEntry m WHERE m.time = c.time AND m.message = c.message)
        ORDER BY c.id
        """,
        "INSERT OR IGNORE INTO main.Config(id, json) SELECT id, json FROM $ALIAS.Config",
        "INSERT OR IGNORE INTO main.State(id, json) SELECT id, json FROM $ALIAS.State",
    ).map { it.trimIndent() }
}
