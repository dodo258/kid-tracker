package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

internal actual fun platformModule(): Module = module {
    single<SqlDriver> { NativeSqliteDriver(Database.Schema, "tracker.db") }
    single { HttpClient(Darwin) }
    single<NetworkMonitor> { IosNetworkMonitor() }
    single<PositionProcessor> { IosBatteryProcessor() }

    single<LocationSource> { IosLocationSource(get(), get(), get()) }

    // 判断层的传感器快照只在 Android 上采（ios/ 不维护）：没有快照，判断层不动，只靠心跳
    single<SensorSource> {
        object : SensorSource {
            override val snapshots: Flow<Snapshot> = emptyFlow()
            override fun setSleeping(sleeping: Boolean) = Unit
        }
    }

    single { MotionActivityDetector(get(), get(), get()) }
    single { RegionDetector(get(), get(), get(), get()) }
    single { IosBackgroundHeartbeat(get(), get(), get()) }

    single<List<SignalSource>> {
        val config = get<Config>()
        buildList {
            if (config.location.stopDetection) {
                add(get<MotionActivityDetector>())
                add(get<RegionDetector>())
                if (config.location.heartbeatIntervalSeconds > 0) {
                    add(get<IosBackgroundHeartbeat>())
                }
            }
        }
    }
}
