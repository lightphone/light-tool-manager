package com.thelightphone.filemanager

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.event.Level
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class FileManagerServiceAndroid(
    private val rootDataProvider: RootDataProvider,
    private val mdnsName: String,
    private val context: Context,
    private val port: Int = SERVER_PORT,
    private val enableLogging: Boolean = false,
) {

    companion object {
        private const val TAG = "FileManagerServiceAndroid"
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
        null
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        if (isRunning) return

        CoroutineScope(Dispatchers.IO).launch { rootDataProvider.refreshProviders() }

        val engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
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

            module(rootDataProvider, enableLogging = enableLogging, logHandler = handler)
        }.start(wait = false)
        server = engine

        registerMdns()
    }

    fun stop() {
        val s = server
        val j = jmdns
        val lock = multicastLock
        server = null
        jmdns = null
        multicastLock = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { j?.unregisterAllServices() }
            runCatching { j?.close() }
            runCatching { lock?.release() }
            s?.stop(1, 5, TimeUnit.SECONDS)
        }
    }

    private fun registerMdns() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Acquire multicast lock so we can receive mDNS packets on WiFi
                val wifi =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wifi.createMulticastLock("filemanager-mdns")
                lock.setReferenceCounted(true)
                lock.acquire()
                multicastLock = lock

                val addr = getWifiAddress(wifi)
                if (addr.hostAddress == "0.0.0.0") {
                    Log.w(TAG, "No WiFi connection, skipping mDNS registration")
                    lock.release()
                    multicastLock = null
                    return@launch
                }
                val jmdnsInstance = JmDNS.create(addr, "$mdnsName.local")
                jmdns = jmdnsInstance

                val serviceInfo = ServiceInfo.create(
                    "_http._tcp.local.",
                    mdnsName,
                    port,
                    "File Manager"
                )
                jmdnsInstance.registerService(serviceInfo)
                Log.d(TAG, "mDNS registered: $mdnsName.local at $addr:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register mDNS", e)
            }
        }
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
