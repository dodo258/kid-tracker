package org.traccar.client.sense

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.traccar.client.ComponentCoroutineScope
import org.traccar.client.Log
import org.traccar.client.State

/**
 * 测试用的传感器录制（设置 → 高级设置，主人 2026-11-17 起用它采真实数据调判断层）。只写本机文件，不上传。
 * 自己起一个 [SnapshotSampler]（不睡、不管追踪开没开），把每 2 秒的快照按 [SnapshotCsv] 一行行写下来，和判断层读的是同一种东西。
 * 开着时拿一个唤醒锁，比平时费电，测完就关。
 *
 * 和根目录旧 App 的录制比（审计 M18）：
 * - 「要录」和「在录」分开：打不开文件、写失败就停下、把开关关掉、记下原因给设置页看，不在每次进程启动时反复重试，也不让异常把进程带崩；
 * - 停的时候文件、采样器、唤醒锁各自释放，一样出错不耽误别的；
 * - 有上限：单个文件到 [FILE_MAX] 换新文件，全部超过 [TOTAL_MAX] 删最旧的并记日志。
 */
object SnapshotRecorder {
    private const val PREFS = "sensor_recorder"
    private const val KEY_ON = "on"
    private const val KEY_ERROR = "error"
    private const val FLUSH_ROWS = 15
    private const val CHECK_ROWS = 300
    const val FILE_MAX = 32L * 1024 * 1024
    const val TOTAL_MAX = 256L * 1024 * 1024

    private class Session(
        val scope: ComponentCoroutineScope,
        var file: File,
        var writer: BufferedWriter,
        val wakeLock: PowerManager.WakeLock?,
    )

    private var session: Session? = null
    @Volatile private var rows = 0

    /** 只在 App 私有目录（审计中 11：以前在外部存储，别的 App 可能读到）；导出走分享，share_plus 用它的 FileProvider 临时授权给选的那个 App */
    fun dir(context: Context): File = File(context.filesDir, "recorder")

    private fun legacyDir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "recorder") }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 进程起来时（开机、闹钟、打开 App）按开关接着录；外部存储里旧版本留下的录制在后台搬进私有目录 */
    fun onProcessStart(context: Context) {
        val app = context.applicationContext
        thread(name = "recorder-move") {
            try {
                moveRecordings(legacyDir(app), dir(app), TOTAL_MAX)
            } catch (e: Exception) {
                Log.log("Recorder: moving old recordings failed: $e")
            }
        }
        if (prefs(context).getBoolean(KEY_ON, false)) start(context)
    }

    fun setOn(context: Context, on: Boolean): Map<String, Any?> {
        prefs(context).edit { putBoolean(KEY_ON, on).remove(KEY_ERROR) }
        if (on) start(context) else stop()
        return status(context)
    }

    fun files(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()

    /** 删掉不在写的文件 */
    fun clear(context: Context): Map<String, Any?> {
        val current = session?.file
        files(context).filter { it != current }.forEach { it.delete() }
        return status(context)
    }

    fun status(context: Context): Map<String, Any?> {
        val all = files(context)
        return mapOf(
            "on" to prefs(context).getBoolean(KEY_ON, false),
            "running" to (session != null),
            "rows" to rows,
            "files" to all.size,
            "bytes" to all.sumOf { it.length() },
            "error" to prefs(context).getString(KEY_ERROR, null),
        )
    }

    @Synchronized
    private fun start(context: Context) {
        if (session != null) return
        val app = context.applicationContext
        val (file, writer) = try {
            openFile(app)
        } catch (e: IOException) {
            fail(app, "open", e)
            return
        }
        rows = 0
        @SuppressLint("WakelockTimeout")
        val wakeLock = app.getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traccar:recorder")
            ?.apply { setReferenceCounted(false); acquire() }
        val scope = ComponentCoroutineScope()
        val s = Session(scope, file, writer, wakeLock)
        session = s
        val sampler = SnapshotSampler(scope, app, MutableStateFlow(State(enabled = true)))
        scope.launch(Dispatchers.IO) {
            sampler.snapshots.collect { snapshot ->
                try {
                    write(app, s, snapshot)
                } catch (e: IOException) {
                    fail(app, "write", e)
                }
            }
        }
        Log.log("Recorder: recording to ${file.name}")
    }

    @Synchronized
    private fun stop() {
        val s = session ?: return
        session = null
        // 采样器在作用域取消时自己停（退订传感器、退出线程）
        s.scope.cancel()
        synchronized(s) {
            try {
                s.writer.close()
            } catch (e: IOException) {
                Log.log("Recorder: close failed: $e")
            }
        }
        s.wakeLock?.takeIf { it.isHeld }?.release()
        Log.log("Recorder: stopped after $rows rows")
    }

    /** 出错就停：关掉开关（下次进程启动不再自己重试）、记下原因给设置页看 */
    private fun fail(context: Context, what: String, e: Exception) {
        Log.log("Recorder: $what failed, stopping: $e")
        prefs(context).edit { putBoolean(KEY_ON, false).putString(KEY_ERROR, "$what: ${e.message ?: e}") }
        stop()
    }

    private fun openFile(context: Context): Pair<File, BufferedWriter> {
        val dir = dir(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "rec-$name.csv")
        val writer = BufferedWriter(FileWriter(file, true))
        try {
            writer.write(SnapshotCsv.HEADER)
            writer.newLine()
        } catch (e: IOException) {
            runCatching { writer.close() }
            throw e
        }
        return file to writer
    }

    private fun write(context: Context, s: Session, snapshot: Snapshot) = synchronized(s) {
        if (session !== s) return
        val offsetMinutes = TimeZone.getDefault().getOffset(snapshot.time) / 60_000
        s.writer.write(SnapshotCsv.format(snapshot, offsetMinutes))
        s.writer.newLine()
        rows++
        if (rows % FLUSH_ROWS == 0) s.writer.flush()
        if (rows % CHECK_ROWS == 0) enforceLimits(context, s)
    }

    /** 当前文件太大就换新的；全部太大就删最旧的（不删在写的） */
    private fun enforceLimits(context: Context, s: Session) {
        if (s.file.length() >= FILE_MAX) {
            s.writer.close()
            val (file, writer) = openFile(context)
            s.file = file
            s.writer = writer
            Log.log("Recorder: file full, continuing in ${file.name}")
        }
        trimRecordings(dir(context), TOTAL_MAX, keep = s.file)
    }
}
