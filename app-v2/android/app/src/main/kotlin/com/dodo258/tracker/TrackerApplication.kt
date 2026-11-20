package com.dodo258.tracker

import android.app.Application
import org.traccar.client.DeviceStorage
import org.traccar.client.sense.SnapshotRecorder

class TrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 防卸载被停用时没报成的补报；测试用录制按开关接着录（出过错的开关已经关了，不会反复重试）。
        // 两样都读凭据存储里的设置：开机后还没解锁时（SDK 的开机广播把进程叫起来）碰了会抛异常，等解锁再做
        DeviceStorage.whenUnlocked(this) {
            Guard.onProcessStart(this)
            SnapshotRecorder.onProcessStart(this)
        }
    }
}
