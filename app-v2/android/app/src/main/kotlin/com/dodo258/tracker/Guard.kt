package com.dodo258.tracker

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.sharedTracker

/** 防卸载（设备管理员）的系统状态和被停用时的告警。 */
object Guard {
    private const val TAG = "Guard"

    fun adminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(GuardAdminReceiver.component(context))

    /** 进程启动时：上次被停用时没报成（进程当场被杀、还没配置）就补报。 */
    fun onProcessStart(context: Context) {
        if (!adminActive(context)) reportAdminLost(context)
    }

    /**
     * 家长开过、不是用 App 密码关掉的：报一次 alarm=tampering。交给 SDK 的告警发件箱（先存再发、联网补发），
     * 存好才记「报过」；SDK 还没配置（没打开过 App）就下次进程启动再试。
     */
    fun reportAdminLost(context: Context, done: () -> Unit = {}) {
        val store = GuardStore(context)
        if (!store.wantAdmin || store.adminReported) {
            done()
            return
        }
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val tracker = withTimeoutOrNull(STORE_TIMEOUT) { sharedTracker() }
                if (tracker == null) {
                    Log.w(TAG, "Admin disabled, tracker not configured yet; will report later")
                } else {
                    tracker.reportTampering("adminDisabled")
                    GuardStore(app).adminReported = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Admin disabled report failed: $e")
            } finally {
                done()
            }
        }
    }

    private const val STORE_TIMEOUT = 8_000L
}
