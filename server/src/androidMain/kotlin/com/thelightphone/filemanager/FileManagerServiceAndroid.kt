package com.thelightphone.filemanager

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.event.Level
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class FileManagerServiceAndroid(
    private val rootDataProvider: RootDataProvider,
    private val context: Context,
    private val port: Int = HTTPS_PORT,
    private val enableLogging: Boolean = false,
) {

    companion object {
        private const val TAG = "FileManagerServiceAndroid"
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
        null

    var apiKey: String? = null
        private set

    val isRunning: Boolean get() = server != null

    fun start() {
        if (isRunning) return

        CoroutineScope(Dispatchers.IO).launch { rootDataProvider.refreshProviders() }

        val key = generateApiKey()
        apiKey = key
        val keyStore = SslConfig.loadKeyStore()
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
                    Level.ERROR -> Log.e(TAG, msg, throwable)
                    Level.WARN -> Log.w(TAG, msg, throwable)
                    Level.INFO -> Log.i(TAG, msg)
                    Level.DEBUG -> Log.d(TAG, msg)
                    Level.TRACE -> Log.v(TAG, msg)
                }
                Unit
            }

            module(rootDataProvider, enableLogging = enableLogging, apiKey = key, logHandler = handler)
        }.start(wait = false)
        server = engine
    }

    fun stop() {
        val s = server
        server = null
        CoroutineScope(Dispatchers.IO).launch {
            s?.stop(1, 5, TimeUnit.SECONDS)
        }
    }

    fun getHttpsUrl(): String? {
        val key = apiKey ?: return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val addr = getWifiAddress(wifi)
        if (addr.hostAddress == "0.0.0.0") return null
        val domain = addr.hostAddress!!.replace('.', '-') + ".my.local-ip.co"
        return "https://$domain:$port/#$key"
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
