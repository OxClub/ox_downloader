package com.oxclub.universaldownloader.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean {
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isUnmetered(): Boolean {
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Whether downloading is currently permitted by connectivity and the Wi-Fi-only setting. */
    fun isAllowed(wifiOnly: Boolean): Boolean = isOnline() && (!wifiOnly || isUnmetered())

    fun register(onChange: () -> Unit) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange()
        }
        cm.registerDefaultNetworkCallback(callback)
    }
}
