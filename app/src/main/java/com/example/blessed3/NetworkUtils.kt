package com.example.blessed3

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {

    /**
     * True if an active network advertises internet capability.
     *
     * We deliberately do **not** require [NetworkCapabilities.NET_CAPABILITY_VALIDATED]: on some
     * vendors (e.g. Realme/Oppo) that flag can take a long time to flip (or never does on
     * captive‑portal‑less Wi‑Fi). The actual HTTP call updates
     * [ServerClient.serverReachable] based on a real response.
     */
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
