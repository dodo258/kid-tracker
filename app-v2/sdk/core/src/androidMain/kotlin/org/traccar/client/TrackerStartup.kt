package org.traccar.client

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.traccar.client.health.HealthRecorder

internal lateinit var applicationContext: Context
    private set

/**
 * 进程一起来就跑（directBootAware：开机后还没解锁时也跑）。解锁着就先把凭据存储里 SDK 的东西搬到设备保护存储
 * （App 的 LegacyDataMigration 在这之前已经把旧 App 的库降好版本）；没解锁就等解锁那一刻再搬，然后换成解锁后的一套组件（[onUserUnlocked]）。
 */
class TrackerStartup : ContentProvider() {
    override fun onCreate(): Boolean {
        applicationContext = checkNotNull(context).applicationContext
        Elapsed.source = SystemClock::elapsedRealtime
        // 降级、重建、丢弃、写不进存储：记成自检页的「最近一次异常」
        Log.faultSink = { at, kind, message -> HealthRecorder.fault(applicationContext, at, kind, message) }
        TrackerService.ensureNotificationChannel(applicationContext)
        if (DeviceStorage.isUnlocked(applicationContext)) {
            DeviceStorage.migrate(applicationContext)
        } else {
            DeviceStorage.whenUnlocked(applicationContext) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { onUserUnlocked(applicationContext) }
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
