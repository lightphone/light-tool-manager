package com.thelightphone.toolmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.thelightphone.toolmanager.datatree.RootDataTree
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * While USB tethering is up, serves the Tool Manager over plain HTTP on the tether interface only,
 * and announces it over mDNS there as `<hostname>.local` (plus an `_http._tcp` service).
 */
internal class TetherHttpServer(
    private val context: Context,
    private val rootDataProvider: RootDataTree,
    private val logger: Logger,
    private val auth: ToolManagerAuth?,
    private val hostname: String,
    private val port: Int,
    private val enableLogging: Boolean,
) {
    private companion object {
        const val TAG = "TetherHttpServer"

        // hidden ConnectivityManager.ACTION_TETHER_STATE_CHANGED, sticky
        const val TETHER_STATE_CHANGED_ACTION = "android.net.conn.TETHER_STATE_CHANGED"

        // USB tethering interface names: RNDIS and NCM both show up as usbN on most kernels
        val USB_TETHER_INTERFACE = Regex("(usb|rndis|ncm)\\d+")
    }

    // JmDNS and Netty startup block on network I/O, and tether broadcasts arrive on the main
    // thread, so all state changes run here, one at a time.
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, TAG).apply { isDaemon = true } }

    /**
     * The tether interface's addresses. IPv6 is the interface's link-local address: answering
     * AAAA queries with it (and serving on it) keeps resolvers from waiting out a missing AAAA
     * answer, which made every `.local` lookup take 5s on macOS.
     */
    private data class TetherAddresses(val ipv4: Inet4Address, val ipv6: Inet6Address?)

    // Written on the executor, read from any thread via ipv4Address.
    @Volatile private var bound: TetherAddresses? = null

    /** The tether interface's IPv4 address while serving, else null. */
    val ipv4Address: String? get() = bound?.ipv4?.hostAddress
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val mdns = mutableListOf<JmDNS>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleRefreshes()
    }

    fun start() {
        // Sticky, so this also delivers the current state right away.
        context.applicationContext.registerReceiver(receiver, IntentFilter(TETHER_STATE_CHANGED_ACTION))
        scheduleRefreshes()
    }

    fun stop() {
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
        executor.execute { teardown() }
        executor.shutdown()
    }

    // The broadcast can arrive before the interface has its address, with nothing sent once it
    // does, so look again a few times over the next several seconds.
    private fun scheduleRefreshes() {
        if (executor.isShutdown) return
        for (delayMs in longArrayOf(0, 1_000, 3_000, 7_000)) {
            executor.schedule(::refresh, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun refresh() {
        val addresses = findUsbTetherAddresses()
        if (addresses == bound) return
        logger.log(TAG, "USB tether addresses changed: ${addresses?.ipv4?.hostAddress} ${addresses?.ipv6?.hostAddress}")
        teardown()
        if (addresses != null) bringUp(addresses)
    }

    private fun bringUp(addresses: TetherAddresses) {
        try {
            server = embeddedServer(Netty, configure = {
                enableHttp2 = false
                for (address in listOfNotNull(addresses.ipv4, addresses.ipv6)) {
                    connector {
                        host = address.hostAddress!!
                        port = this@TetherHttpServer.port
                    }
                }
            }) {
                module(rootDataProvider, enableLogging = enableLogging, toolManagerLogger = logger, auth = auth)
            }.start(wait = false)
            bound = addresses

            // One JmDNS per address family, each answering for the same hostname. The service
            // is only registered once so it doesn't show up twice.
            mdns += JmDNS.create(addresses.ipv4, hostname).also {
                it.registerService(ServiceInfo.create("_http._tcp.local.", "Light Phone", port, "path=/"))
            }
            addresses.ipv6?.let { mdns += JmDNS.create(it, hostname) }
            logger.log(TAG, "Serving http://$hostname.local:$port/ on ${addresses.ipv4.hostAddress}")
        } catch (e: Throwable) {
            // Also logged plainly: some Logger implementations send reportError elsewhere.
            logger.log(TAG, "Couldn't start tether server: $e")
            logger.reportError(TAG, e, "Couldn't start tether server")
            teardown()
        }
    }

    private fun teardown() {
        mdns.forEach { runCatching { it.unregisterAllServices(); it.close() } }
        mdns.clear()
        server?.stop(500, 2_000, TimeUnit.MILLISECONDS)
        server = null
        if (bound != null) logger.log(TAG, "Stopped tether server on ${bound?.ipv4?.hostAddress}")
        bound = null
    }

    private fun findUsbTetherAddresses(): TetherAddresses? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        for (iface in interfaces) {
            if (!USB_TETHER_INTERFACE.matches(iface.name) || !runCatching { iface.isUp }.getOrDefault(false)) continue
            val addresses = iface.inetAddresses.toList()
            val ipv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress } ?: continue
            val ipv6 = addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
            return TetherAddresses(ipv4, ipv6)
        }
        return null
    }
}
