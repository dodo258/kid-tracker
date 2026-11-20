package org.traccar.client.health

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.content.getSystemService
import kotlinx.serialization.json.Json
import org.traccar.client.DeviceStorage
import org.traccar.client.Fault
import org.traccar.client.Log
import org.traccar.client.NetworkMonitor
import org.traccar.client.UploadObserver

/**
 * 厂商项实际表现的记录（[BackgroundHealth] 的 Android 侧）：呼吸闹钟挂上 / 响了、每次上报、开机广播时记一笔，
 * 存在 SDK 自己的 SharedPreferences「background_health」里（JSON 一条，设备保护存储，没解锁时开机广播、闹钟也能记），进程被杀、重启后接着用。
 * 记录出错只写日志，不影响定位和上报。
 */
object HealthRecorder {
    private const val PREFS = "background_health"
    private const val KEY = "record"
    private val json = Json { ignoreUnknownKeys = true }

    /** 这次开机的时刻（墙钟毫秒） */
    fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun screenOff(context: Context): Boolean =
        context.getSystemService<PowerManager>()?.isInteractive == false

    fun breathScheduled(context: Context, delayMillis: Long) = update(context) {
        val now = System.currentTimeMillis()
        BackgroundHealth.breathScheduled(it, now, now + delayMillis)
    }

    fun breathCancelled(context: Context) = update(context, BackgroundHealth::breathCancelled)

    fun breathFired(context: Context) = update(context) {
        BackgroundHealth.breathFired(it, System.currentTimeMillis(), screenOff(context))
    }

    fun uploaded(context: Context, success: Boolean, online: Boolean) = update(context) {
        BackgroundHealth.uploaded(it, success, screenOff(context), online)
    }

    fun bootReceived(context: Context, tracking: Boolean) = update(context) {
        BackgroundHealth.bootReceived(it, bootTime(), System.currentTimeMillis(), tracking)
    }

    /** 看一眼并给出三项结论：`{"reboot": {"verdict": "ok", "detail": 80}, "breath": …, "send": …}` */
    fun status(context: Context, tracking: Boolean): Map<String, Map<String, Any?>> {
        val record = update(context) { BackgroundHealth.check(it, System.currentTimeMillis(), bootTime(), tracking) }
        return Evidence.entries.associate { evidence ->
            val judgement = BackgroundHealth.judge(record, evidence)
            evidence.name.lowercase() to mapOf("verdict" to judgement.verdict.name.lowercase(), "detail" to judgement.detail)
        }
    }

    /** 最近一次异常（Log.fault 交过来的） */
    fun fault(context: Context, at: Long, kind: Fault, message: String) = update(context) {
        BackgroundHealth.fault(it, at, kind.name.lowercase(), message)
    }

    /** 自检页「最近一次异常」：`{"time": 毫秒, "kind": "rescue", "message": "…"}`，没有是 null */
    fun lastFault(context: Context): Map<String, Any>? = update(context) { it }.let { r ->
        val at = r.faultAt ?: return null
        mapOf("time" to at, "kind" to (r.faultKind ?: ""), "message" to (r.fault ?: ""))
    }

    /** 家长按了「去设置」：这一项重新验证 */
    fun reset(context: Context, evidence: Evidence) = update(context) { BackgroundHealth.reset(it, evidence, bootTime()) }

    @Synchronized
    private fun update(context: Context, block: (HealthRecord) -> HealthRecord): HealthRecord {
        val prefs = DeviceStorage.preferences(context, PREFS)
        val stored = prefs.getString(KEY, null)?.let {
            try {
                json.decodeFromString<HealthRecord>(it)
            } catch (e: Exception) {
                Log.log("Background health record unreadable, starting over: $e")
                null
            }
        } ?: HealthRecord()
        val next = try {
            block(BackgroundHealth.withFingerprint(stored, Build.FINGERPRINT))
        } catch (e: Exception) {
            Log.log("Background health update failed: $e")
            return stored
        }
        if (next != stored) prefs.edit { putString(KEY, json.encodeToString(next)) }
        return next
    }
}

/** 每次上报后记一笔：灭屏时发不发得出去（自检清单「允许联网」「锁屏后不断网」） */
class HealthUploadObserver(private val context: Context, private val network: NetworkMonitor) : UploadObserver {
    override fun onUpload(success: Boolean) {
        HealthRecorder.uploaded(context, success, network.isOnline.value)
    }
}
