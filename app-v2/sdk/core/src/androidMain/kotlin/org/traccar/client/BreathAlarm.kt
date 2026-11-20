package org.traccar.client

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.traccar.client.health.HealthRecorder

/**
 * 呼吸闹钟（[KeepAlive] 的 [WakeAlarm]）：`setExactAndAllowWhileIdle`，打盹时也按时响，响了允许在后台拉起前台服务。
 * 精确闹钟权限：Android 13+ 的 USE_EXACT_ALARM 安装即授予，12 和不认它的系统靠 SCHEDULE_EXACT_ALARM（DECISIONS「精确闹钟权限」）；
 * 拿不到时退回 `setAndAllowWhileIdle`（打盹时会被拖到 9 分钟以上）并记日志。
 *
 * 原版的心跳闹钟用不精确的 `setAndAllowWhileIdle`、默认不开。不换会怎样：打盹时 5 分钟的呼吸被系统合并、拖长，
 * 家长页按 15 分钟判失联时会误报。
 */
class BreathAlarm(context: Context) : WakeAlarm {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager = checkNotNull(appContext.getSystemService())
    private var exactDenied = false

    override fun scheduleIn(delayMillis: Long) {
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis
        val intent = pendingIntent()
        // 自检清单「锁屏时呼吸准不准」：记下该几点响
        HealthRecorder.breathScheduled(appContext, delayMillis)
        if (AlarmManagerCompat.canScheduleExactAlarms(alarmManager)) {
            try {
                AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
                exactDenied = false
                return
            } catch (e: SecurityException) {
                if (!exactDenied) Log.log("Breath alarm: exact alarm denied, using inexact: $e")
            }
        } else if (!exactDenied) {
            Log.log("Breath alarm: exact alarms not allowed, using inexact")
        }
        exactDenied = true
        AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intent)
    }

    override fun cancel() {
        alarmManager.cancel(pendingIntent())
        HealthRecorder.breathCancelled(appContext)
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(appContext, BreathReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal companion object {
        // 进程被闹钟叫起来时保活还没开始收：Channel 存着等它来取，取一次就没了（重建出来的那一套不会重放旧闹钟）
        private val alarms = Channel<Unit>(Channel.CONFLATED)
        val fired: Flow<Unit> = alarms.receiveAsFlow()

        fun fire() {
            alarms.trySend(Unit)
        }
    }
}

/**
 * 闹钟响了：系统只在 onReceive 里拿着唤醒锁，先拿自己的（有上限），进程是被叫起来的就按存着的配置恢复跟踪，再交给保活。
 * 日志库在 sharedTracker() 里才打开，新进程里先写的日志会丢，所以恢复完再写。
 */
class BreathReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BreathWake.hold(context, KeepAlive.BREATH_HOLD_LIMIT.inWholeMilliseconds)
        // 先记响的时刻（灭屏时准不准），保活处理时会挂下一次
        HealthRecorder.breathFired(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val tracker = try {
                    sharedTracker().also { BootRestore.succeeded() }
                } catch (e: Exception) {
                    BreathWake.release()
                    // 读不出配置、状态（存储暂时出错）：过一会儿再响一次接着试，不然没解锁时再没有入口把追踪拉起来
                    BootRestore.failed(context, "Breath alarm: restore failed: ${e.rootCause()}")
                    return@launch
                }
                Log.log("Breath alarm fired")
                if (tracker?.state?.value?.enabled == true) BreathAlarm.fire() else BreathWake.release()
            } catch (e: Exception) {
                BreathWake.release()
                Log.log("Breath alarm failed: ${e.rootCause()}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** 呼吸时的唤醒锁：不计数、每次都带上限（[KeepAlive] 干完活就放，忘了放到点系统也放） */
internal object BreathWake : WakeHold {
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun hold(context: Context, limitMillis: Long) {
        val lock = wakeLock ?: context.applicationContext.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traccar:breath")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        lock?.acquire(limitMillis)
    }

    override fun hold(limitMillis: Long) = hold(applicationContext, limitMillis)

    @Synchronized
    override fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }
}
