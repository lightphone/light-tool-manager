package com.thelightphone.toolmanager

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresPermission
import com.thelightphone.toolmanager.datatree.RootDataTree
import io.ktor.http.encodeURLPath
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

data class LocalNetworkInfo(
    val transport: Transport,
    val ssid: String?,
    val ipAddress: String?,
    // null means undeterminable (requires API 33+); true means no password/open network
    val isOpenNetwork: Boolean?
) {
    enum class Transport { WIFI, ETHERNET, HOTSPOT }
}

class ToolManagerServiceAndroid(
    private val rootDataProvider: RootDataTree,
    private val context: Context,
    private val logger: Logger,
    private val port: Int = HTTPS_PORT,
    private val enableLogging: Boolean = false,
    private val onNetworkLost: (() -> Unit)? = null,
    private val onNetworkNeedsApproval: ((LocalNetworkInfo) -> Boolean)? = null,
    private val provideNewAuth: () -> ToolManagerAuth? = { null }
) {

    companion object {
        private const val TAG = "ToolManagerServiceAndroid"
        // hidden WifiManager constant, broadcast when the hotspot is toggled
        private const val WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
        null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hotspotReceiver: BroadcastReceiver? = null

    var auth: ToolManagerAuth? = null
        private set

    val isRunning: Boolean get() = server != null

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun start(): Boolean {
        if (isRunning) return true

        val networkInfo = getLocalNetworkInfo()
        if (networkInfo == null) {
            logger.log(TAG, "No WiFi, hotspot, or ethernet connection — refusing to start")
            return false
        }

        val approvalCallback = onNetworkNeedsApproval
        if (approvalCallback != null && !approvalCallback(networkInfo)) {
            return false
        }

        return startInternal()
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun confirmStart(approvedNetwork: LocalNetworkInfo): Boolean {
        if (isRunning) return true

        val currentNetwork = getLocalNetworkInfo()
        if (currentNetwork == null || currentNetwork != approvedNetwork) {
            logger.log(TAG, "Network changed since approval — refusing to start")
            return false
        }

        return startInternal()
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun startInternal(): Boolean {
        CoroutineScope(Dispatchers.IO).launch { rootDataProvider.refreshProviders() }

        auth = provideNewAuth()
        val sslConfig = SslConfig(logger)
        val keyStore = sslConfig.loadKeyStore(File(context.filesDir, "certs"))
        // not important since we're using local-ip.co's certs which are public
        val keyStorePassword = "whatever"

        val engine = embeddedServer(Netty, configure = {
            // ALPN negotiation (needed for HTTP/2) goes through Netty's JdkAlpnSslEngine, which
            // isn't fully compatible with Android's SSLEngine implementation. fine for this app
            enableHttp2 = false
            sslConnector(
                keyStore = keyStore,
                keyAlias = "server",
                keyStorePassword = { keyStorePassword.toCharArray() },
                privateKeyPassword = { keyStorePassword.toCharArray() }
            ) {
                this.port = this@ToolManagerServiceAndroid.port
                host = "0.0.0.0"
            }
        }) {
            module(
                rootDataProvider,
                enableLogging = enableLogging,
                auth = auth,
                toolManagerLogger = logger
            )
        }.start(wait = false)
        server = engine

        registerNetworkMonitor()
        return true
    }

    fun stop() {
        unregisterNetworkMonitor()
        val s = server
        server = null
        auth = null
        CoroutineScope(Dispatchers.IO).launch {
            s?.stop(1, 5, TimeUnit.SECONDS)
        }
    }

    // path, if given, is a tree node path (e.g. "Photos/Vacation") the URL deep links to once the
    // client has authenticated.
    fun getHttpsUrl(hostOverride: InetAddress? = null, path: String? = null): String? {
        val key = auth?.primaryKey ?: return null
        val address = hostOverride ?: getLocalAddress() ?: return null
        val domain = address.hostAddress!!.replace('.', '-') + ".my.local-ip.co"
        val deepLink = path?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/" + it.encodeURLPath() }.orEmpty()
        return "https://$domain:$port/#$key$deepLink"
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun hasLocalNetwork(): Boolean = getLocalNetworkInfo() != null

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun getLocalNetworkInfo(): LocalNetworkInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> LocalNetworkInfo.Transport.WIFI
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> LocalNetworkInfo.Transport.ETHERNET
            getTetherAddress(cm) != null -> LocalNetworkInfo.Transport.HOTSPOT
            else -> return null
        }

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid: String?
        val isOpenNetwork: Boolean?
        if (transport == LocalNetworkInfo.Transport.WIFI) {
            // requires location permission on older API levels, otherwise returns "<unknown ssid>"
            @Suppress("DEPRECATION")
            val wifiInfo = wifi.connectionInfo
            ssid = wifiInfo?.ssid?.trim('"')
            isOpenNetwork = wifiInfo?.isOpen
        } else {
            ssid = null
            isOpenNetwork = null
        }
        val ipAddress = runCatching { getLocalAddress()?.hostAddress }.getOrNull()

        return LocalNetworkInfo(transport, ssid, ipAddress, isOpenNetwork)
    }

    private val WifiInfo.isOpen: Boolean? get() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // no reliable API below API 33 for the security type of the currently connected
            // network — scan results are best-effort and may not include the active AP
            return null
        }
        return when (currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN, WifiInfo.SECURITY_TYPE_OWE -> true
            WifiInfo.SECURITY_TYPE_UNKNOWN -> null
            else -> false
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun registerNetworkMonitor() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            override fun onLost(network: Network) {
                if (!hasLocalNetwork()) {
                    logger.log(TAG, "Local network lost — stopping server")
                    stop()
                    onNetworkLost?.invoke()
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)

        // this should catch hotspot going away
        val receiver = object : BroadcastReceiver() {
            @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            override fun onReceive(context: Context, intent: Intent) {
                if (isRunning && !hasLocalNetwork()) {
                    logger.log(TAG, "Hotspot lost — stopping server")
                    stop()
                    onNetworkLost?.invoke()
                }
            }
        }
        hotspotReceiver = receiver
        context.applicationContext.registerReceiver(receiver, IntentFilter(WIFI_AP_STATE_CHANGED_ACTION))
    }

    private fun unregisterNetworkMonitor() {
        hotspotReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        hotspotReceiver = null
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    // address clients on the local network can reach us at
    // tries the active Wi-Fi/ethernet network first, falls back to tether address if we're hotspot
    private fun getLocalAddress(): InetAddress? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val isLocal = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        if (isLocal) {
            cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.let { return it }
        }
        return getTetherAddress(cm)
    }

    // when hosting a hotspot there's no WifiInfo IP; look for a private IPv4 address on an
    // interface other than the upstream one.
    private fun getTetherAddress(cm: ConnectivityManager): InetAddress? {
        val upstream = runCatching {
            cm.activeNetwork?.let { cm.getLinkProperties(it)?.interfaceName }
        }.getOrNull()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
            ?: return null
        return interfaces
            .asSequence()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .filter { !it.isLoopback && !it.isVirtual && it.name != upstream }
            .filter { !it.name.startsWith("tun") && !it.name.startsWith("rmnet") && !it.name.startsWith("dummy") }
            .sortedByDescending { it.name.startsWith("ap") || it.name.contains("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }
}
