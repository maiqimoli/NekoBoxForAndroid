@file:OptIn(ObsoleteCoroutinesApi::class, DelicateCoroutinesApi::class)

package io.nekohasekai.sagernet.utils

import androidx.annotation.RequiresApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage() {
            val completed = CompletableDeferred<Unit>()
        }
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage() {
            val completed = CompletableDeferred<Unit>()
        }

        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    private val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkActor = listenerScope.actor<NetworkMessage>(capacity = Channel.UNLIMITED) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        val pendingRequests = arrayListOf<NetworkMessage.Get>()
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                try {
                    if (listeners.isEmpty()) register()
                    listeners[message.key] = message.listener
                    if (network != null) runCatching {
                        message.listener(network)
                    }.onFailure { Logs.w(it) }
                } finally {
                    message.completed.complete(Unit)
                }
            }
            is NetworkMessage.Get -> {
                check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                if (network == null) pendingRequests += message else message.response.complete(
                    network
                )
            }
            is NetworkMessage.Stop -> {
                try {
                    val removed = listeners.remove(message.key)
                    if (removed != null && listeners.isEmpty()) {
                        network = null
                        unregister()
                        pendingRequests.forEach {
                            it.response.completeExceptionally(
                                UnknownHostException("Default network listener stopped")
                            )
                        }
                        pendingRequests.clear()
                        runCatching { removed(null) }.onFailure { Logs.w(it) }
                    }
                } finally {
                    message.completed.complete(Unit)
                }
            }

            is NetworkMessage.Put -> {
                network = message.network
                pendingRequests.forEach { it.response.complete(message.network) }
                pendingRequests.clear()
                listeners.values.forEach { listener ->
                    runCatching { listener(network) }.onFailure { Logs.w(it) }
                }
            }
            is NetworkMessage.Update -> if (network == message.network) listeners.values.forEach { listener ->
                runCatching { listener(network) }.onFailure { Logs.w(it) }
            }
            is NetworkMessage.Lost -> if (network == message.network) {
                network = null
                listeners.values.forEach { listener ->
                    runCatching { listener(null) }.onFailure { Logs.w(it) }
                }
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) {
        val message = NetworkMessage.Start(key, listener)
        networkActor.send(message)
        message.completed.await()
    }

    suspend fun get() = if (fallback) {
        SagerNet.connectivity.activeNetwork
            ?: throw UnknownHostException() // failed to listen, return current if available
    } else NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any) {
        val message = NetworkMessage.Stop(key)
        networkActor.send(message)
        message.completed.await()
    }

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = send(NetworkMessage.Put(network))

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) = send(NetworkMessage.Update(network))

        override fun onLost(network: Network) = send(NetworkMessage.Lost(network))

        private fun send(message: NetworkMessage) {
            if (networkActor.trySend(message).isFailure) {
                Logs.w("Default network event dropped: ${message.javaClass.simpleName}")
            }
        }
    }

    @Volatile
    private var fallback = false
    private var registered = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @RequiresApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, Callback, mainHandler
                    )
                }
                in 28 until 31 -> @RequiresApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, Callback, mainHandler)
                }
                in 26 until 28 -> @RequiresApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                }
                in 24 until 26 -> @RequiresApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
            registered = true
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
            registered = false
        }
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        runCatching {
            SagerNet.connectivity.unregisterNetworkCallback(Callback)
        }.onFailure(Logs::w)
    }
}
