package org.traccar.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import org.traccar.client.alert.AlertOutbox
import org.traccar.client.alert.AlertStore

internal fun coreModule(): Module = module {
    single { ConfigStore(get()) }
    // 库写不进去（存储满）时点先放内存，见 ResilientQueue
    // 内存缓冲整个进程一份，重建组件时不丢（QueueMemory）
    single<PositionQueue> { ResilientQueue(DatabaseQueue(get()), QueueMemory.process) }
    single<Flow<Position>> { get<LocationSource>().positions }
    single<StateFlow<State>> { get<StateStore>().state }
    single { ComponentCoroutineScope() }

    single<Uploader> { HttpUploader(get(), get(), getOrNull()) }

    single { AlertStore(get()) }
    single {
        val engine = get<TrackerEngine>()
        AlertOutbox(
            store = get(),
            uploader = get(),
            processor = get(),
            isOnline = get<NetworkMonitor>().isOnline,
            scope = get<ComponentCoroutineScope>(),
            latestGps = { engine.latestGps },
            describe = engine::current,
            retry = getOrNull(),
        )
    }

    single {
        TrackerEngine(
            stateStore = get(),
            queue = get(),
            network = get(),
            locationSource = get(),
            sensors = get(),
            signalSources = get(),
            processors = listOf(get()),
            uploader = get(),
            buffer = get<Config>().buffer,
            // 引擎的参数是 CoroutineScope（测试好传），Koin 里登记的是 ComponentCoroutineScope，要写明类型，不然解析不到、追踪起不来
            scope = get<ComponentCoroutineScope>(),
            elapsed = Elapsed::now,
        )
    }

    single {
        Tracker(
            config = get(),
            stateStore = get(),
            locationSource = get(),
            batteryProcessor = get(),
            uploader = get(),
            componentScope = get(),
            engine = get(),
            alerts = get(),
        )
    }
}

/** 平台给告警的输入（Android：电量、关机、定位开关的广播），建起来就开始盯，见 install() */
interface AlertSources

internal expect fun platformModule(): Module
