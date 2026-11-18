package org.squidify.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide view of real internet availability.
 *
 * A connected Wi-Fi/mobile transport is not enough: Android's VALIDATED
 * capability means the system has confirmed that the active network reaches
 * the public internet.
 */
class NetworkMonitor(context: Context) {
    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _hasValidatedInternet = MutableStateFlow(readValidatedInternet())
    val hasValidatedInternet: StateFlow<Boolean> = _hasValidatedInternet.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            refresh()
        }
    }

    init {
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        refresh()
    }

    private fun refresh() {
        _hasValidatedInternet.value = readValidatedInternet()
    }

    private fun readValidatedInternet(): Boolean = runCatching {
        val active = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
