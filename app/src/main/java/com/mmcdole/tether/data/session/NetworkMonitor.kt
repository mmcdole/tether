package com.mmcdole.tether.data.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface NetworkEvent {
    data class Available(val network: Long) : NetworkEvent
    data class Lost(val network: Long) : NetworkEvent
}

/** Networks coming and going. Networks are identified by their handle. */
interface NetworkMonitor {
    /** The network new sockets use, or null when offline. */
    fun defaultNetwork(): Long?

    val events: Flow<NetworkEvent>
}

class AndroidNetworkMonitor @Inject constructor(@ApplicationContext context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun defaultNetwork(): Long? = connectivity.activeNetwork?.networkHandle

    override val events: Flow<NetworkEvent> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkEvent.Available(network.networkHandle))
            }

            override fun onLost(network: Network) {
                trySend(NetworkEvent.Lost(network.networkHandle))
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }
}
