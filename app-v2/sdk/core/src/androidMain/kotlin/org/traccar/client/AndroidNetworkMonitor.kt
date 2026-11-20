package org.traccar.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** 联网状态（系统的默认网络回调）。回调登记在组件作用域 [scope] 上，组件结束时注销（审计中 5） */
class AndroidNetworkMonitor(context: Context, scope: CoroutineScope) : NetworkMonitor {

    private val connectivityManager: ConnectivityManager =
        checkNotNull(context.applicationContext.getSystemService())
    override val isOnline = MutableStateFlow(currentStatus())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            update(capabilities.isUsable())
        }

        override fun onLost(network: Network) {
            update(false)
        }

        override fun onUnavailable() {
            update(false)
        }
    }

    init {
        scope.registerWhileActive(
            register = { connectivityManager.registerDefaultNetworkCallback(callback) },
            unregister = {
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: IllegalArgumentException) {
                    Log.log("Network callback already unregistered: $e")
                }
            },
        )
    }

    private fun update(online: Boolean) {
        if (isOnline.value != online) Log.log("Network ${if (online) "online" else "offline"}")
        isOnline.value = online
    }

    private fun currentStatus(): Boolean =
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.isUsable() == true

    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
