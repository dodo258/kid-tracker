package com.dodo258.tracker

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var guard: GuardChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        BackgroundCheck(this).register(messenger)
        UpdateChannel(this).register(messenger)
        RecorderChannel(applicationContext).register(messenger)
        guard = GuardChannel(this).also { it.register(messenger) }
    }

    // 每次启动、回到前台都设一次：这个标记跟着任务走，任务没了再从桌面打开是新任务，要重新设。
    // 只在运行时按品牌设，不写进清单的 excludeFromRecents，别的品牌照旧留在多任务里能加锁。
    override fun onResume() {
        super.onResume()
        if (!Brand.excludeFromRecents(Brand.of(Build.MANUFACTURER))) return
        for (task in getSystemService(ActivityManager::class.java).appTasks) {
            try {
                task.setExcludeFromRecents(true)
            } catch (e: IllegalArgumentException) {
                // 任务刚好被系统移除时抛这个；它已经不在多任务里了
                Log.w(TAG, "setExcludeFromRecents: $e")
            }
        }
    }

    @Deprecated("Flutter 的 FlutterActivity 仍走这个回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (guard?.onActivityResult(requestCode) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
