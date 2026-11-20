package com.dodo258.tracker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log

/**
 * 进程一启动、在原版 SDK（它的 TrackerStartup initOrder 是 200）和 Flutter 之前跑：
 * 覆盖安装后第一次启动可能来自开机/升级广播，不一定打开过 App，所以不能放在 Dart 里。
 * initOrder 越大越先跑。
 */
class LegacyDataMigration : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = checkNotNull(context)
        val file = context.getDatabasePath(DATABASE)
        if (!file.exists()) return true
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                LegacyTrackerDb.migrate(AndroidSql(db))?.let { from ->
                    Log.i(TAG, "$DATABASE migrated from version $from to ${LegacyTrackerDb.TARGET_SCHEMA_VERSION}")
                }
            }
        } catch (e: Exception) {
            // 迁移在事务里，失败时库还是旧版本，SDK 打开就崩：改名放一边让 SDK 建新库，不删（没发出去的点和告警还在备份里）。
            // 设备号、服务器、密码在 Flutter 的设置里，不受影响，打开 App 会重新交给 SDK。
            // 主库连同日志整组搬不动时原样留着、下次启动再迁（审计 11.0.11 第 5 条：以前这时直接删库，已提交没发出去的记录全丢）
            val backup = LegacyTrackerDb.setAside(file)
            if (backup != null) {
                Log.e(TAG, "$DATABASE migration failed, kept as ${backup.name}", e)
            } else {
                Log.e(TAG, "$DATABASE migration failed and could not be set aside, keeping it to retry next start", e)
            }
        }
        return true
    }

    private class AndroidSql(private val db: SQLiteDatabase) : LegacyTrackerDb.Sql {
        override var version: Int
            get() = db.version
            set(value) {
                db.version = value
            }

        override fun exec(sql: String, args: List<Any?>) = db.execSQL(sql, args.toTypedArray())

        override fun queryString(sql: String): String? =
            db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }

        override fun query(sql: String): List<List<Any?>> = db.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        (0 until c.columnCount).map { i ->
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                else -> null
                            }
                        },
                    )
                }
            }
        }

        override fun transaction(block: () -> Unit) {
            db.beginTransaction()
            try {
                block()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "LegacyDataMigration"
        /** 原版 SDK 的库名（sdk/core 的 PlatformModule.kt），和旧 App 一样 */
        const val DATABASE = "tracker.db"
    }
}
