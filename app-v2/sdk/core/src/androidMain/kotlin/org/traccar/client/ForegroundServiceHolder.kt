package org.traccar.client

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 原版的前台服务，保活的底。和原版不同的一处：组件作用域取消（改设置、看门狗重建）时不停服务，只有主人关追踪时才停。
 * 原版改设置时先停服务、新的一套再起：在前台改设置没事，看门狗在后台重建时再起前台服务可能被系统拦下
 * （安卓 12 起后台起前台服务要豁免），服务一停进程就可能被杀。
 */
class ForegroundServiceHolder(
    scope: ComponentCoroutineScope,
    context: Context,
    state: StateFlow<State>,
) : SignalSource, ForegroundService {

    override val signals: Flow<Signal> = emptyFlow()

    private val appContext = context.applicationContext

    override val running: Boolean get() = TrackerService.running

    override val demoted: Boolean get() = TrackerService.isDemoted(appContext)

    init {
        scope.launch {
            state.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) start() else stop()
            }
        }
    }

    override fun start() = TrackerService.start(appContext)

    private fun stop() {
        appContext.stopService(Intent(appContext, TrackerService::class.java))
    }
}

class TrackerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        running = true
        Log.log("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground(NotificationConfig())) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            sharedTracker()?.let { startInForeground(it.config.notification) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Log.log("Service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(settings: NotificationConfig): Boolean {
        if (!hasNotificationPermission(this)) return true
        val notification = buildNotification(settings)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            Log.log("Foreground start not allowed: $e")
            false
        } catch (e: SecurityException) {
            Log.log("Foreground start denied: $e")
            false
        }
    }

    private fun buildNotification(settings: NotificationConfig): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracker_notification)
            .setContentText(settings.text)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "tracker"
        private const val NOTIFICATION_ID = 0x7AC0

        /** 服务在不在（这个进程里）：呼吸闹钟响时不在就拉起来 */
        @Volatile
        internal var running = false
            private set

        /** 起前台服务；后台起被系统拦下（安卓 12 起要豁免）只写日志，等呼吸闹钟、开机广播再拉 */
        internal fun start(context: Context) {
            val intent = Intent(context, TrackerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasNotificationPermission(context)) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                Log.log("Service start blocked: $e")
            }
        }

        /**
         * 服务在却不是前台服务：电池「受限」时系统把它降成普通服务（logcat `am_foreground_service_stop … STOP_FOREGROUND`），
         * 有电池优化豁免时不会接着停掉它，[running] 一直为真；解除受限后要重新拉成前台。
         * 只能问系统：降级不经过 [Service.stopForeground]，服务自己看不出来（旧版 24c85fa 同样做法）。
         */
        @Suppress("DEPRECATION") // getRunningServices 只返回本 App 的服务，正是这里要的
        internal fun isDemoted(context: Context): Boolean {
            if (!hasNotificationPermission(context)) return false // 没有通知权限时本来就只能做普通服务
            val info = context.getSystemService<ActivityManager>()
                ?.getRunningServices(Int.MAX_VALUE)
                ?.firstOrNull { it.service.className == TrackerService::class.java.name }
                ?: return false
            return !info.foreground
        }

        internal fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

        internal fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService<NotificationManager>() ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
