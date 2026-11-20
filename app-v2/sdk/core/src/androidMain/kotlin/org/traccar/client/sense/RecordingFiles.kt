package org.traccar.client.sense

import java.io.File
import java.io.IOException
import org.traccar.client.Log

/**
 * 旧版本把录制写在外部存储（getExternalFilesDir）：CSV 里有精确经纬度、Wi-Fi 名和 BSSID，Android 10 上有存储权限的 App 能读（审计中 11）。
 * 进程启动时搬进 App 私有目录 [to]（录制现在写在那里），搬完删掉外面的；搬不动（空间不够）就留着下次再搬，不丢主人还没拉走的数据。
 * 搬完按 [totalMax] 删最旧的。返回搬了几个。
 */
internal fun moveRecordings(from: File?, to: File, totalMax: Long): Int {
    val legacy = from?.listFiles()?.filter { it.isFile } ?: return 0
    if (legacy.isEmpty()) return 0
    if (!to.isDirectory && !to.mkdirs()) return 0
    var moved = 0
    for (file in legacy) {
        val target = File(to, file.name)
        try {
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                if (!file.delete()) Log.log("Recorder: could not delete external ${file.name}")
            }
            moved++
        } catch (e: IOException) {
            target.delete()
            Log.log("Recorder: moving ${file.name} out of external storage failed, will retry: $e")
        }
    }
    from.delete()
    trimRecordings(to, totalMax, keep = null)
    if (moved > 0) Log.log("Recorder: moved $moved recordings from external storage to app storage")
    return moved
}

/** 录制合计超过 [totalMax] 删最旧的（文件名带时间，按名字排就是按时间），不删 [keep]（在写的那个） */
internal fun trimRecordings(dir: File, totalMax: Long, keep: File?) {
    val all = dir.listFiles()?.filter { it.name.endsWith(".csv") }?.sortedBy { it.name } ?: return
    var total = all.sumOf { it.length() }
    for (old in all) {
        if (total <= totalMax) break
        if (old == keep) continue
        total -= old.length()
        old.delete()
        Log.log("Recorder: over ${totalMax / 1048576} MB, deleted oldest ${old.name}")
    }
}
