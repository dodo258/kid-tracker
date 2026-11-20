package org.traccar.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.UserManagerCompat
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.traccar.client.db.Database

/**
 * 没解锁也要恢复上报（Direct Boot）：设了锁屏密码的手机开机后、孩子第一次解锁前，凭据存储读不了，只有设备保护存储能用。
 * SDK 自己的东西都放设备保护存储：tracker.db（配置里的服务器地址和设备号、追踪开关和运动状态、离线队列、待发告警、日志），
 * 两个 SharedPreferences（告警「这一轮报过了」、自检清单的实际表现）。App 密码、防卸载记录等 App 自己的设置仍只在凭据存储。
 *
 * 之前的版本放在凭据存储：解锁着的时候搬过来（[migrate]），两边都有就逐行合并（[StorageMerge]），不丢不重。
 */
object DeviceStorage {
    internal const val DATABASE = "tracker.db"

    /** SDK 用到的 SharedPreferences（AlertMonitor、TrackerExtensions 共用一个，HealthRecorder 一个） */
    private val PREFERENCES = listOf("traccar-client-sdk", "background_health")

    internal fun context(context: Context): Context = context.applicationContext.createDeviceProtectedStorageContext()

    internal fun preferences(context: Context, name: String): SharedPreferences =
        context(context).getSharedPreferences(name, Context.MODE_PRIVATE)

    fun isUnlocked(context: Context): Boolean = UserManagerCompat.isUserUnlocked(context)

    /**
     * 解锁着就马上跑 [block]，没解锁就等解锁那一刻（ACTION_USER_UNLOCKED 只发给动态注册的接收器）跑一次。
     * App 碰凭据存储的启动工作（防卸载补报、测试用录制）也走这里：没解锁时碰凭据存储会抛异常、把进程带崩。
     */
    fun whenUnlocked(context: Context, block: () -> Unit) {
        val app = context.applicationContext
        if (isUnlocked(app)) {
            block()
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                app.unregisterReceiver(this)
                block()
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // 注册前那一刻刚好解锁了：广播不会再来
        if (isUnlocked(app)) {
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                return // 广播已经到了，block 已经跑过
            }
            block()
        }
    }

    /**
     * 把凭据存储里 SDK 的东西搬到设备保护存储；只能在解锁后调用。出错只写日志，下次接着搬。
     * 解锁那一刻 [onUserUnlocked] 和开机广播会同时来搬，排队一个个来
     */
    @Synchronized
    fun migrate(context: Context) {
        val credential = context.applicationContext
        val device = context(credential)
        PREFERENCES.forEach { name ->
            try {
                mergePreferences(credential, device, name)
            } catch (e: Exception) {
                Log.log("Device storage: preferences $name not moved: ${e.rootCause()}")
            }
        }
        try {
            mergeDatabase(credential, device)
        } catch (e: Exception) {
            Log.log("Device storage: $DATABASE not moved: ${e.rootCause()}")
        }
    }

    /** 设备保护存储里没有的键从凭据存储拿过来（有的是更新的，不覆盖），再删掉凭据存储里的 */
    private fun mergePreferences(credential: Context, device: Context, name: String) {
        val old = credential.getSharedPreferences(name, Context.MODE_PRIVATE).all
        if (old.isEmpty()) return
        val target = device.getSharedPreferences(name, Context.MODE_PRIVATE)
        val current = target.all.keys
        target.edit(commit = true) {
            old.filterKeys { it !in current }.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        credential.deleteSharedPreferences(name)
        Log.log("Device storage: preferences $name moved")
    }

    private fun mergeDatabase(credential: Context, device: Context) {
        val old = credential.getDatabasePath(DATABASE)
        if (!old.exists()) return
        // 库版本比 SDK 新：旧 App 留下的（第 7 版），要等 App 的 LegacyDataMigration 降好版本（它不是 directBootAware，解锁后才跑），
        // 开机广播 BOOT_COMPLETED 时再搬
        val version = SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        if (version > Database.Schema.version) {
            Log.log("Device storage: $DATABASE version $version not migrated by app yet, moving later")
            return
        }
        // 常见情况：升级到这一版后第一次起来，设备保护存储里还没有库，整个搬过去（同一分区内改名，带上 -wal、-journal）
        if (!device.getDatabasePath(DATABASE).exists() && device.moveDatabaseFrom(credential, DATABASE)) {
            Log.log("Device storage: $DATABASE moved")
            return
        }
        // 两边都有：先把两边都升到当前库版本，再逐行并进来，并完删掉老的（删失败下次再并也不会重复）
        listOf(credential, device).forEach { upgrade(it) }
        SQLiteDatabase.openDatabase(device.getDatabasePath(DATABASE).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE ? AS ${StorageMerge.ALIAS}", arrayOf(old.path))
            try {
                db.beginTransaction()
                try {
                    StorageMerge.statements.forEach(db::execSQL)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE ${StorageMerge.ALIAS}")
            }
        }
        credential.deleteDatabase(DATABASE)
        Log.log("Device storage: $DATABASE merged")
    }

    /** 用 SQLDelight 打开一次：库版本旧的按迁移脚本升上来，没有的表建上 */
    private fun upgrade(context: Context) {
        AndroidSqliteDriver(Database.Schema, context, DATABASE).use { driver ->
            Database(driver).configQueries.selectConfig().executeAsOneOrNull()
        }
    }
}
