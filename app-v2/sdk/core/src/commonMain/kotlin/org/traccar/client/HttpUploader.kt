package org.traccar.client

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.round

class HttpUploader(
    private val config: Config,
    private val httpClient: HttpClient,
    private val observer: UploadObserver? = null,
) : Uploader {

    override suspend fun upload(position: Position): Boolean = send(position).also { observer?.onUpload(it) }

    private suspend fun send(position: Position): Boolean = try {
        val response: HttpResponse = httpClient.submitForm(
            url = config.serverUrl,
            formParameters = Parameters.build {
                append("id", config.deviceId)
                position.latitude?.let { append("lat", it.toString()) }
                position.longitude?.let { append("lon", it.toString()) }
                append("timestamp", (position.time / 1000).toString())
                position.accuracy?.let { append("accuracy", it.toString()) }
                position.altitude?.let { append("altitude", it.toString()) }
                position.speed?.let { append("speed", it.toKnots().toString()) }
                position.bearing?.let { append("bearing", it.toString()) }
                position.battery?.let { append("batt", it.toString()) }
                position.charging?.let { append("charge", it.toString()) }
                position.alarm?.let { append("alarm", it) }
                // 防护告警（tampering）的原因：服务器通知模板按它分文案（adminDisabled、passwordFailures）
                position.reason?.let { append("reason", it) }
                // 判断层的字段（方案第 4 节）。不能叫 wifi、cell：那是 OsmAnd 协议的保留参数，Traccar 解析不了会丢掉整条
                position.provider?.let { append("provider", it) }
                position.state?.let { append("state", it) }
                position.motion?.let { append("motion", it) }
                position.steps?.let { append("steps", it.toString()) }
                position.bumps?.let { append("bumps", it.toString()) }
                position.net?.let { append("net", it) }
                position.netLat?.let { append("netLat", it.toString()) }
                position.netLon?.let { append("netLon", it.toString()) }
                position.netAcc?.let { append("netAcc", it.toString()) }
                position.netSource?.let { append("netSource", it) }
                position.wifiSame?.let { append("wifiSame", it.toString()) }
                // 开机后还没解锁：家长页写「手机刚重启」
                position.locked?.let { append("locked", it.toString()) }
            },
        ) {
            config.headers.forEach { (name, value) -> headers.append(name, value) }
        }
        Log.log("Upload response ${response.status.value}")
        response.status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.log("Upload error: ${e.message}")
        false
    }

    private fun Double.toKnots(): Double = round(this * 1.94384 * 100) / 100
}
