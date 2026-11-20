package org.traccar.traccar_client_sdk

import android.Manifest
import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.traccar.client.Accuracy
import org.traccar.client.Config
import org.traccar.client.LocationConfig
import org.traccar.client.NotificationConfig
import org.traccar.client.hasPermission
import org.traccar.client.health.Evidence
import org.traccar.client.health.HealthRecorder
import org.traccar.client.requestPosition
import org.traccar.client.sharedTracker
import org.traccar.client.startTracking

class TraccarClientSdkPlugin :
    FlutterPlugin,
    MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "traccar_client_sdk")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "init" -> scope.launchHandler(result) {
                sharedTracker(parseConfig(call.arguments as Map<*, *>))
                null
            }
            "setConfig" -> scope.launchHandler(result) {
                sharedTracker()!!.updateConfig(parseConfig(call.arguments as Map<*, *>))
                null
            }
            "start" -> scope.launchHandler(result) {
                sharedTracker()!!.startTracking(context)
                null
            }
            "stop" -> scope.launchHandler(result) {
                sharedTracker()?.stop()
                null
            }
            "requestPosition" -> scope.launchHandler(result) {
                val alarm = (call.arguments as? Map<*, *>)?.get("alarm") as? String
                sharedTracker()?.requestPosition(context, alarm) ?: false
            }
            // 求救（R5）：不弹权限框，有定位权限才现取位置；先存本机再发，返回服务器收到没有
            "sendSos" -> scope.launchHandler(result) {
                val locate = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                val tracker = sharedTracker() ?: throw IllegalStateException("Tracker not configured")
                val retry = ((call.arguments as? Map<*, *>)?.get("retry") as? Number)?.toLong()
                val send = tracker.sendSos(locate, retry)
                mapOf("id" to send.id, "sent" to send.sent)
            }
            "isAlertSent" -> scope.launchHandler(result) {
                val id = ((call.arguments as Map<*, *>)["id"] as Number).toLong()
                sharedTracker()?.isAlertSent(id) ?: false
            }
            // 防护告警（R6）：设备管理员被停用、App 密码连续输错 5 次；先存再发
            "reportTampering" -> scope.launchHandler(result) {
                val reason = (call.arguments as Map<*, *>)["reason"] as String
                val tracker = sharedTracker() ?: throw IllegalStateException("Tracker not configured")
                tracker.reportTampering(reason)
            }
            // 自检清单（R6）：厂商设置有没有生效，按实际表现判（重启后自己恢复、锁屏时呼吸准不准、锁屏时能不能发出去）
            "getHealth" -> scope.launchHandler(result) {
                HealthRecorder.status(context, sharedTracker()?.state?.value?.enabled == true)
            }
            // 自检页「最近一次异常」（可靠性要求第 8 条）：降级、重建、丢弃、写不进存储
            "getLastFault" -> scope.launchHandler(result) {
                HealthRecorder.lastFault(context)
            }
            "resetHealth" -> scope.launchHandler(result) {
                HealthRecorder.reset(context, Evidence.valueOf(((call.arguments as Map<*, *>)["evidence"] as String).uppercase()))
                null
            }
            "isTracking" -> scope.launchHandler(result) {
                sharedTracker()?.state?.value?.enabled ?: false
            }
            "getLogs" -> scope.launchHandler(result) {
                sharedTracker()?.getLogs()?.map {
                    mapOf("time" to it.time, "message" to it.message)
                } ?: emptyList<Map<String, Any>>()
            }
            "clearLogs" -> scope.launchHandler(result) {
                sharedTracker()?.clearLogs()
                null
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    private fun CoroutineScope.launchHandler(result: Result, block: suspend () -> Any?) {
        launch {
            try {
                result.success(block())
            } catch (e: Throwable) {
                result.error(e::class.simpleName ?: "error", e.message, null)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(args: Map<*, *>): Config {
        val location = args["location"] as Map<*, *>
        val notification = args["notification"] as Map<*, *>
        return Config(
            serverUrl = args["serverUrl"] as String,
            headers = args["headers"] as? Map<String, String> ?: emptyMap(),
            deviceId = args["deviceId"] as String,
            location = LocationConfig(
                accuracy = Accuracy.valueOf(location["accuracy"] as String),
                distanceMeters = (location["distanceMeters"] as Number).toInt(),
                intervalSeconds = (location["intervalSeconds"] as Number).toInt(),
                angleDegrees = (location["angleDegrees"] as Number).toInt(),
                stopDetection = location["stopDetection"] as Boolean,
                stopTimeoutSeconds = (location["stopTimeoutSeconds"] as Number).toInt(),
                stationaryRadiusMeters = (location["stationaryRadiusMeters"] as Number).toInt(),
                heartbeatIntervalSeconds = (location["heartbeatIntervalSeconds"] as Number).toInt(),
            ),
            wakeLock = args["wakeLock"] as Boolean,
            buffer = args["buffer"] as Boolean,
            preferPlatformProviders = args["preferPlatformProviders"] as Boolean,
            notification = NotificationConfig(text = notification["text"] as String),
        )
    }
}
