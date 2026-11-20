package com.dodo258.tracker

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.traccar.client.sense.SnapshotRecorder

/** 设置页「传感器录制（测试用）」：开关、状态（含出错原因）、导出用的文件路径、清掉旧文件。录制本身在 SDK（SnapshotRecorder）。 */
class RecorderChannel(private val context: Context) {
    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "status" -> result.success(SnapshotRecorder.status(context))
                "setOn" -> result.success(SnapshotRecorder.setOn(context, call.arguments as Boolean))
                "files" -> result.success(SnapshotRecorder.files(context).map { it.absolutePath })
                "clear" -> result.success(SnapshotRecorder.clear(context))
                else -> result.notImplemented()
            }
        }
    }

    private companion object {
        const val CHANNEL = "com.dodo258.tracker/recorder"
    }
}
