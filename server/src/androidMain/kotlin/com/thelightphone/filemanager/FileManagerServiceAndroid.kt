package com.thelightphone.filemanager

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.annotation.RequiresPermission
import com.thelightphone.filemanager.datatree.RootDataTree
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class FileManagerServiceAndroid(
    private val rootDataProvider: RootDataTree,
    private val context: Context,
    private val logger: Logger,
    private val port: Int = HTTPS_PORT,
    private val enableLogging: Boolean = false,
    private val onNetworkLost: (() -> Unit)? = null,
    private val provideNewAuth: () -> FileManagerAuth? = { null }
) {

    companion object {
        private const val TAG = "FileManagerServiceAndroid"
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
        null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var auth: FileManagerAuth? = null
        private set

    val isRunning: Boolean get() = server != null

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun start(): Boolean {
        if (isRunning) return true

        if (!hasLocalNetwork()) {
            logger.log(TAG, "No WiFi, hotspot, or ethernet connection — refusing to start")
            return false
        }

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
                this.port = this@FileManagerServiceAndroid.port
                host = "0.0.0.0"
            }
        }) {
            module(
                rootDataProvider,
                enableLogging = enableLogging,
                auth = auth,
                fileManagerLogger = logger
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

    fun getHttpsUrl(hostOverride: InetAddress? = null): String? {
        val key = auth?.primaryKey ?: return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val address = hostOverride ?: getWifiAddress(wifi)
        if (address.hostAddress == "0.0.0.0") return null
        val domain = address.hostAddress!!.replace('.', '-') + ".my.local-ip.co"
        return "https://$domain:$port/#$key"
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun hasLocalNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun registerNetworkMonitor() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_USB)
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
    }

    private fun unregisterNetworkMonitor() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun getWifiAddress(wifi: WifiManager): InetAddress {
        @Suppress("DEPRECATION")
        val ip = wifi.connectionInfo.ipAddress
        val bytes = byteArrayOf(
            (ip and 0xFF).toByte(),
            (ip shr 8 and 0xFF).toByte(),
            (ip shr 16 and 0xFF).toByte(),
            (ip shr 24 and 0xFF).toByte()
        )
        return InetAddress.getByAddress(bytes)
    }
}
