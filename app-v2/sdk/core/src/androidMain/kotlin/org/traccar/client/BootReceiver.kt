package org.traccar.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.traccar.client.health.HealthRecorder

/**
 * 开机、更新后自启（原版就有 BOOT_COMPLETED 和 MY_PACKAGE_REPLACED）：按存着的配置和状态恢复跟踪，追踪开着就由
 * [ForegroundServiceHolder] 拉起前台服务、[KeepAlive] 挂上呼吸闹钟，判断层第一份传感器快照到了就报一条。
 * 这几个广播都允许在后台起前台服务。
 *
 * 比原版多收：
 * - LOCKED_BOOT_COMPLETED（directBootAware）：设了锁屏密码的手机开机后、第一次解锁前就发。配置、状态、离线点都在设备保护存储
 *   （[DeviceStorage]），这时就恢复上报，只用系统定位，报的点带 locked（家长页写「手机刚重启」）。不收的话要等孩子解锁
 *   （旧 App 在 P20 上实测解锁后约 80 秒），夜里系统更新重启、没电关机后充电自己开机，孩子没解锁前家长一直看不到位置。
 *   解锁后的 BOOT_COMPLETED 再把这一套换成完整的（用高德），解锁那一刻 [onUserUnlocked] 已经换过的就不再换。
 * - 两个快速开机广播：部分国产机和 HTC 的快速开机只发 QUICKBOOT_POWERON、不发 BOOT_COMPLETED，不收就不自启
 *   （根目录旧 App 一直收着）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 解锁后的开机广播：凭据存储里还有没搬的（没解锁时装的升级包、App 的旧库迁移解锁后才跑）再搬一次，没有就什么都不做
                if (DeviceStorage.isUnlocked(context)) DeviceStorage.migrate(context)
                // 读不出配置、状态（存储暂时出错）：退避重试；没配置、追踪关着不算失败
                val tracker = try {
                    sharedTracker().also { BootRestore.succeeded() }
                } catch (e: Exception) {
                    BootRestore.failed(context, "Boot restore failed: ${e.rootCause()}")
                    return@launch
                }
                val tracking = tracker?.state?.value?.enabled == true
                Log.log("Boot received: ${intent.action}, tracking $tracking, unlocked ${DeviceStorage.isUnlocked(context)}")
                // 已经起着（没解锁时那次开机广播起的）：服务被后台限制拦过就再拉一次，没解锁时建的那一套换成完整的
                if (tracking && !TrackerService.running) TrackerService.start(context)
                if (intent.action != ACTION_LOCKED_BOOT) rebuildIfLocked()
                // 自检清单「重启后自己恢复」：只算开机，不算覆盖安装；一次开机两条广播只记先到的那条
                if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && !bootRecorded) {
                    bootRecorded = true
                    HealthRecorder.bootReceived(context, tracking)
                }
            } catch (e: Exception) {
                Log.log("Boot restore failed: ${e.rootCause()}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_LOCKED_BOOT = Intent.ACTION_LOCKED_BOOT_COMPLETED

        /** 这个进程里记过开机了（LOCKED_BOOT_COMPLETED 之后同一次开机还会来 BOOT_COMPLETED） */
        @Volatile
        var bootRecorded = false

        val ACTIONS = setOf(
            ACTION_LOCKED_BOOT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}

/** 恢复跟踪失败后用呼吸闹钟退避重试（[RestoreRetry]）；开机广播和呼吸闹钟两处共用 */
internal object BootRestore {
    private val retry = RestoreRetry()

    fun succeeded() = retry.succeeded()

    fun failed(context: Context, reason: String) {
        val delay = retry.failed()
        Log.log("$reason, retrying in ${delay / 1000}s")
        try {
            BreathAlarm(context).scheduleIn(delay)
        } catch (e: Exception) {
            Log.log("Restore retry alarm not set: $e")
        }
    }
}
