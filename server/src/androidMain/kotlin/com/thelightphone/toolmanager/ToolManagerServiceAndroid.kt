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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

        // Longest a start waits for the previous run to finish shutting down (see stopJob).
        private const val PREVIOUS_STOP_WAIT_MS = 3_000L
    }

    sealed interface ServerTransport {
        data object Wifi : ServerTransport
        data class UsbTether(val hostname: String) : ServerTransport
    }

    private sealed interface ActiveServer {
        suspend fun stop()
        class Lan(
            val engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        ) : ActiveServer {
            // Ktor's stop blocks (grace period + timeout), so keep it off the caller's thread.
            override suspend fun stop() {
                withContext(Dispatchers.IO) { engine.stop(1, 5, TimeUnit.SECONDS) }
            }
        }

        class Tether(val server: TetherHttpServer, val hostname: String) : ActiveServer {
            override suspend fun stop() = server.stop()
        }
    }

    // Volatile: read from the tether server's thread when its addresses change.
    @Volatile private var activeServer: ActiveServer? = null

    private val _tetherEndpoint = MutableStateFlow<TetherEndpoint?>(null)

    /**
     * Where a computer on the USB cable reaches the tool manager while running over USB tether,
     * else null. Updates when the server starts/stops and when the tether addresses change.
     */
    val tetherEndpoint: StateFlow<TetherEndpoint?> = _tetherEndpoint.asStateFlow()

    // The previous run's shutdown, so a quick restart can wait for it to release the port
    private var stopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hotspotReceiver: BroadcastReceiver? = null

    var auth: ToolManagerAuth? = null
        private set

    val isRunning: Boolean get() = activeServer != null

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun start(transport: ServerTransport): Boolean {
        if (isRunning) return true
        if (transport is ServerTransport.UsbTether) return startInternal(transport)

        val networkInfo = getLocalNetworkInfo()
        if (networkInfo == null) {
            logger.log(TAG, "No WiFi, hotspot, or ethernet connection — refusing to start")
            return false
        }

        val approvalCallback = onNetworkNeedsApproval
        if (approvalCallback != null && !approvalCallback(networkInfo)) {
            return false
        }

        return startInternal(transport)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun confirmStart(approvedNetwork: LocalNetworkInfo): Boolean {
        if (isRunning) return true

        val currentNetwork = getLocalNetworkInfo()
        if (currentNetwork == null || currentNetwork != approvedNetwork) {
            logger.log(TAG, "Network changed since approval — refusing to start")
            return false
        }

        return startInternal(ServerTransport.Wifi)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun startInternal(transport: ServerTransport): Boolean {
        // block for a bit if we're waiting for a previous shutdown to complete
        stopJob?.let { runBlocking { withTimeoutOrNull(PREVIOUS_STOP_WAIT_MS) { it.join() } } }
        stopJob = null

        CoroutineScope(Dispatchers.IO).launch { rootDataProvider.refreshProviders() }

        auth = provideNewAuth()
        when (transport) {
            ServerTransport.Wifi -> {
                activeServer = ActiveServer.Lan(startLanHttps())
                // after setting activeServer: its (sticky) hotspot broadcast checks isRunning
                registerNetworkMonitor()
            }

            is ServerTransport.UsbTether -> {
                lateinit var tether: ActiveServer.Tether
                val tetherServer = TetherHttpServer(
                    rootDataProvider, logger, auth, transport.hostname, SERVER_PORT, enableLogging,
                    onAddressesChanged = { publishTetherEndpoint(tether) },
                )
                tether = ActiveServer.Tether(tetherServer, transport.hostname)
                activeServer = tether
                // after activeServer, so an address change from before this is either applied here
                // or by its own callback
                _tetherEndpoint.value = tether.endpoint()
                tetherServer.start()
            }
        }
        return true
    }

    private fun startLanHttps(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val sslConfig = SslConfig(logger)
        val keyStore = sslConfig.loadKeyStore(File(context.filesDir, "certs"))
        // not important since we're using local-ip.co's certs which are public
        val keyStorePassword = "whatever"

        return embeddedServer(Netty, configure = {
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
    }

    // Returns right away (callers are Android callbacks, often on the main thread); the shutdown
    // itself runs in the background as stopJob.
    fun stop() {
        unregisterNetworkMonitor()
        val stopping = activeServer
        activeServer = null
        _tetherEndpoint.value = null
        auth = null
        if (stopping != null) {
            stopJob = CoroutineScope(Dispatchers.IO).launch { stopping.stop() }
        }
    }

    // path, if given, is a tree node path (e.g. "Photos/Vacation") the URL deep links to once the
    // client has authenticated.
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun getHttpsUrl(hostOverride: InetAddress? = null, path: String? = null): String? {
        val key = auth?.primaryKey ?: return null
        val address = hostOverride ?: getLocalAddress() ?: return null
        val domain = address.hostAddress!!.replace('.', '-') + ".my.local-ip.co"
        val deepLink =
            path?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/" + it.encodeURLPath() }.orEmpty()
        return "https://$domain:$port/#$key$deepLink"
    }

    // tether details: the .local name, the tether IPs, and the current key (see TetherEndpoint's
    // URL extensions).
    private fun ActiveServer.Tether.endpoint() = TetherEndpoint(
        "$hostname.local",
        server.ipv4Address,
        SERVER_PORT,
        auth?.primaryKey,
        server.ipv6Address,
    )

    private fun publishTetherEndpoint(tether: ActiveServer.Tether) {
        // only accept updates from the active server
        _tetherEndpoint.update { current -> if (activeServer === tether) tether.endpoint() else current }
    }

    fun currentPairingCode(): PairingCode? = auth?.currentCode()

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

    private val WifiInfo.isOpen: Boolean?
        get() {
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
        context.applicationContext.registerReceiver(
            receiver,
            IntentFilter(WIFI_AP_STATE_CHANGED_ACTION)
        )
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
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
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
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    private fun getTetherAddress(cm: ConnectivityManager): InetAddress? {
        val upstream = runCatching {
            cm.activeNetwork?.let { cm.getLinkProperties(it)?.interfaceName }
        }.getOrNull()
        val interfaces =
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
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
