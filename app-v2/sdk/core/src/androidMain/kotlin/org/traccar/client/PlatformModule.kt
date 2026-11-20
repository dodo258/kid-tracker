package org.traccar.client

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import org.traccar.client.alert.AlertRetry
import org.traccar.client.db.Database
import org.traccar.client.health.HealthUploadObserver
import org.traccar.client.sense.SnapshotSampler

internal actual fun platformModule(): Module = module {
    single<Context> { applicationContext }
    // 设备保护存储：开机后还没解锁也能读写（DeviceStorage）
    single<SqlDriver> { AndroidSqliteDriver(Database.Schema, DeviceStorage.context(get()), DeviceStorage.DATABASE) }
    // 原版不设时限（Android 引擎默认连接、读各 100 秒，整次请求不限）：服务器收了不回时一次上传能挂很久。
    // 引擎和告警发件箱外面还各有一层总时限，这里让连接、读写到点真的断开、放掉连接
    single {
        HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
            }
        }
    }
    single<NetworkMonitor> { AndroidNetworkMonitor(get(), get<ComponentCoroutineScope>()) }
    single<PositionProcessor> { AndroidBatteryProcessor(get()) }
    // 自检清单：灭屏时发不发得出去
    single<UploadObserver> { HealthUploadObserver(get(), get()) }

    // 高德为主、系统定位兜底；设置里选了「使用系统定位」或打包时没有高德 Key 就只用系统定位。
    // 原版的 Fused（Google 服务）不装：国内手机没有 Google 服务，P20 装了但用不了，单次定位会挂住不回。
    // 每个定位源的单次取点都套一层 [BoundedLocationSource]，定位源卡住也不拖住引擎；连续超时交给看门狗，第一级只重建定位源
    // （[RebuildableLocationSource]：在组件作用域的子作用域里建，重建时整个取消）
    single<LocationSource> {
        val context = get<Context>()
        val config = get<Config>()
        val components = get<ComponentCoroutineScope>()
        val state = get<StateFlow<State>>()
        val online = get<NetworkMonitor>().isOnline
        // 高德和系统定位各数各的（审计高 6）：备用源按时回来不清主源连续超时的数
        val watch = { name: String -> StuckWatch("$name fetch timed out") { rescueSharedTracker(components, it) } }
        val locked = !DeviceStorage.isUnlocked(context)
        lockedComponents = components.takeIf { locked }
        RebuildableLocationSource(components) { scope ->
            locationSource(scope, context, config, state, online, watch, locked)
        }
    }

    single { WakeLockHolder(get(), get(), get()) }
    single { ForegroundServiceHolder(get(), get(), get()) }

    // 保活：呼吸闹钟（唯一的定时唤醒）兼看门狗，见 KeepAlive
    single {
        val scope = get<ComponentCoroutineScope>()
        KeepAlive(
            scope = scope,
            state = get(),
            engine = get<TrackerEngine>(),
            alarm = BreathAlarm(get()),
            service = get<ForegroundServiceHolder>(),
            wake = BreathWake,
            fired = BreathAlarm.fired,
            onStuck = { rescueSharedTracker(scope, it) },
        )
    }

    // 告警（R5）：没发出去时挂联网任务补发；追踪开着时盯电量、关机、定位开关
    single<AlertRetry> { AndroidAlertRetry(get()) }
    single<AlertSources> { AlertMonitor(get(), get(), get(), get()) }

    // 传感器快照（判断层的输入）：加速度、陀螺批量上报，静止时只挂显著运动这个硬件唤醒（方案 3b）
    single<SensorSource> { SnapshotSampler(get(), get(), get()) }

    // 原版的静止检测靠 Google 活动识别（进静止、出静止）和 Google 地理围栏（出静止），国内手机都用不了（没有 Google 服务时
    // 报 ActivityRecognition.API is not available、地理围栏报 1000），不装；现在由判断层按传感器判断（policy/、motion/）。
    // 原版的心跳闹钟换成保活的呼吸闹钟（KeepAlive，不经信号，直接叫引擎）
    single<List<SignalSource>> {
        val config = get<Config>()
        buildList {
            if (config.wakeLock) {
                add(get<WakeLockHolder>())
            }
            add(get<ForegroundServiceHolder>())
        }
    }
}

/** 按设置和手机状态建定位源（高德为主、系统定位兜底，或只用系统定位），建在 [scope] 里 */
private fun locationSource(
    scope: ComponentCoroutineScope,
    context: Context,
    config: Config,
    state: StateFlow<State>,
    online: StateFlow<Boolean>,
    watch: (name: String) -> StuckWatch,
    locked: Boolean,
): LocationSource {
    val systemWatch = watch("System location")
    val system = { gated: StateFlow<State> ->
        BoundedLocationSource(AndroidLocationSource(scope, context, gated), scope, "System location", watch = systemWatch)
    }
    val amapWatch = watch("AMap")
    return when {
        // 开机后还没解锁：高德 SDK 自己用 SharedPreferences、SQLite 和文件（location 11.3.101 的 jar 里各有 11、5、10 个类），
        // 都在凭据存储，这时读写会抛异常、可能把进程带崩，先只用系统定位；解锁后重建（UserUnlock.kt）
        locked -> {
            Log.log("Device locked since boot, using system location until unlocked")
            system(state)
        }
        config.preferPlatformProviders -> {
            Log.log("Using system location only (setting)")
            system(state)
        }
        amapApiKey(context) == null -> {
            Log.log("No AMap key, using system location only")
            system(state)
        }
        else -> FallbackLocationSource(
            scope = scope,
            state = state,
            policy = amapFallbackPolicy(),
            isOnline = online,
            primaryName = "AMap",
            fallbackName = "system location",
            createPrimary = { gated, onResult ->
                BoundedLocationSource(AMapLocationSource(scope, context, gated, onResult), scope, "AMap", watch = amapWatch)
            },
            createFallback = system,
        )
    }
}
