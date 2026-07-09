package com.telcoagent.udpclient

import android.content.Context

object AnalysisPreferences {
    private const val PREFS = "netprobe_analysis"

    private const val KEY_SERVER_ADDRESS = "server_address"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_SERVER_PUBLIC_KEY = "server_public_key"
    private const val KEY_CLIENT_PRIVATE_KEY = "client_private_key"
    private const val KEY_CLIENT_ADDRESS = "client_address"
    private const val KEY_ALLOWED_IPS = "allowed_ips"
    private const val KEY_DNS_SERVERS = "dns_servers"

    fun getServerAddress(context: Context): String =
        prefs(context).getString(KEY_SERVER_ADDRESS, "") ?: ""

    fun setServerAddress(context: Context, value: String) =
        prefs(context).edit().putString(KEY_SERVER_ADDRESS, value).apply()

    fun getServerPort(context: Context): Int =
        prefs(context).getInt(KEY_SERVER_PORT, 51820)

    fun setServerPort(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SERVER_PORT, value).apply()

    fun getServerPublicKey(context: Context): String =
        prefs(context).getString(KEY_SERVER_PUBLIC_KEY, "") ?: ""

    fun setServerPublicKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_SERVER_PUBLIC_KEY, value).apply()

    fun getClientPrivateKey(context: Context): String =
        prefs(context).getString(KEY_CLIENT_PRIVATE_KEY, "") ?: ""

    fun setClientPrivateKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CLIENT_PRIVATE_KEY, value).apply()

    fun getClientAddress(context: Context): String =
        prefs(context).getString(KEY_CLIENT_ADDRESS, "10.0.0.2/32") ?: "10.0.0.2/32"

    fun setClientAddress(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CLIENT_ADDRESS, value).apply()

    fun getAllowedIps(context: Context): String =
        prefs(context).getString(KEY_ALLOWED_IPS, "0.0.0.0/1, 128.0.0.0/1") ?: "0.0.0.0/1, 128.0.0.0/1"

    fun setAllowedIps(context: Context, value: String) =
        prefs(context).edit().putString(KEY_ALLOWED_IPS, value).apply()

    fun getDnsServers(context: Context): String =
        prefs(context).getString(KEY_DNS_SERVERS, "1.1.1.1") ?: "1.1.1.1"

    fun setDnsServers(context: Context, value: String) =
        prefs(context).edit().putString(KEY_DNS_SERVERS, value).apply()

    fun getSplitTunnelEnabled(context: Context): Boolean =
        prefs(context).getBoolean("split_tunnel_enabled", false)

    fun setSplitTunnelEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("split_tunnel_enabled", value).apply()

    fun getSplitTunnelApps(context: Context): Set<String> =
        prefs(context).getStringSet("split_tunnel_apps", emptySet()) ?: emptySet()

    fun setSplitTunnelApps(context: Context, apps: Set<String>) =
        prefs(context).edit().putStringSet("split_tunnel_apps", apps).apply()

    fun getMtu(context: Context): Int =
        prefs(context).getInt("mtu", 1420)

    fun setMtu(context: Context, value: Int) =
        prefs(context).edit().putInt("mtu", value.coerceIn(576, 1500)).apply()

    fun hasConfig(context: Context): Boolean {
        return getServerAddress(context).isNotBlank() &&
            getServerPublicKey(context).isNotBlank() &&
            getClientPrivateKey(context).isNotBlank() &&
            getClientAddress(context).isNotBlank()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
