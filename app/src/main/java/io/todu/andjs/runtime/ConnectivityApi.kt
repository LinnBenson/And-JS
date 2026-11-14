package io.todu.andjs.runtime

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build

class ConnectivityApi(private val context:Context) {
    fun bluetooth():Map<String,Any?> {
        val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val granted=Build.VERSION.SDK_INT<31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
        return mapOf("available" to (adapter!=null),"granted" to granted,"enabled" to if(granted) adapter?.isEnabled else null,"name" to if(granted) adapter?.name else null)
    }
    @Suppress("DEPRECATION") fun wifi():Map<String,Any?> {
        val manager=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info=manager?.connectionInfo
        val connected=info!=null && info.networkId>=0
        return mapOf("available" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),"enabled" to (manager?.isWifiEnabled ?: false),"ssid" to info?.ssid?.takeUnless {it=="<unknown ssid>"}?.removeSurrounding("\""),"bssid" to info?.bssid?.takeUnless {it=="02:00:00:00:00:00"},"rssi" to info?.rssi?.takeIf {connected && it in -126..0},"frequencyMhz" to info?.frequency?.takeIf {connected && it>0},"linkSpeedMbps" to info?.linkSpeed?.takeIf {connected && it>=0})
    }
    fun network():Map<String,Any?> {
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network=manager.activeNetwork;val caps=manager.getNetworkCapabilities(network);val link=manager.getLinkProperties(network)
        val transports=listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi",NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",NetworkCapabilities.TRANSPORT_VPN to "vpn",NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth").filter {caps?.hasTransport(it.first)==true}.map {it.second}
        return mapOf("connected" to (caps!=null),"validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true),"metered" to manager.isActiveNetworkMetered,"type" to when {caps==null->"none";"vpn" in transports->"vpn";else->transports.firstOrNull() ?: "other"},"transports" to transports,"internet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true),"captivePortal" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true),"vpn" to ("vpn" in transports),"downstreamKbps" to caps?.linkDownstreamBandwidthKbps?.takeIf {it>0},"upstreamKbps" to caps?.linkUpstreamBandwidthKbps?.takeIf {it>0},"interfaceName" to link?.interfaceName,"dns" to link?.dnsServers.orEmpty().mapNotNull {it.hostAddress},"addresses" to link?.linkAddresses.orEmpty().map {mapOf("address" to it.address.hostAddress,"prefixLength" to it.prefixLength)})
    }
}
