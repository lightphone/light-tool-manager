package com.thelightphone.filemanager

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.event.Level
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class FileManagerServiceAndroid(
    private val rootDataProvider: RootDataProvider,
    private val context: Context,
    private val logger: Logger,
    private val port: Int = HTTPS_PORT,
    private val enableLogging: Boolean = false,
    private val onNetworkLost: (() -> Unit)? = null,
) {

    companion object {
        private const val TAG = "FileManagerServiceAndroid"
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
        null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var apiKey: String? = null
        private set

    val isRunning: Boolean get() = server != null

    fun start(): Boolean {
        if (isRunning) return true

        if (!hasLocalNetwork()) {
            logger.log(TAG, "No WiFi, hotspot, or ethernet connection — refusing to start")
            return false
        }

        CoroutineScope(Dispatchers.IO).launch { rootDataProvider.refreshProviders() }

        val key = generateApiKey()
        apiKey = key
        val sslConfig = SslConfig(logger)
        val keyStore = sslConfig.loadKeyStore(File(context.filesDir, "certs"))
        // not important since we're using local-ip.co's certs which are public
        val keyStorePassword = "changeit"

        val engine = embeddedServer(Netty, configure = {
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
            val handler = { level: Level, msg: String, throwable: Throwable? ->
                when (level) {
                    Level.ERROR -> logger.reportError(TAG, Exception(throwable), msg)
                    Level.WARN -> logger.reportError(TAG, Exception(throwable), "WARNING: $msg")
                    Level.INFO,Level.DEBUG,Level.TRACE -> logger.log(TAG, msg)
                }
            }

            module(rootDataProvider, enableLogging = enableLogging, apiKey = key, logHandler = handler)
        }.start(wait = false)
        server = engine

        registerNetworkMonitor()
        return true
    }

    fun stop() {
        unregisterNetworkMonitor()
        val s = server
        server = null
        apiKey = null
        CoroutineScope(Dispatchers.IO).launch {
            s?.stop(1, 5, TimeUnit.SECONDS)
        }
    }

    fun getHttpsUrl(): String? {
        val key = apiKey ?: return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val address = getWifiAddress(wifi)
        if (address.hostAddress == "0.0.0.0") return null
        val domain = address.hostAddress!!.replace('.', '-') + ".my.local-ip.co"
        return "https://$domain:$port/#$key"
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun hasLocalNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun registerNetworkMonitor() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_USB)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
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
