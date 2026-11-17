package com.thelightphone.toolmanager

import com.thelightphone.toolmanager.datatree.RootDataTree
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.concurrent.thread

/**
 * While USB tethering is up, serves the Tool Manager over plain HTTP on the tether interface only,
 * and announces it over mDNS there as `<hostname>.local` (plus an `_http._tcp` service).
 */
internal class TetherHttpServer(
    private val rootDataProvider: RootDataTree,
    private val logger: Logger,
    private val auth: ToolManagerAuth?,
    private val hostname: String,
    private val port: Int,
    private val enableLogging: Boolean,
) {
    private companion object {
        const val TAG = "TetherHttpServer"

        // USB tethering interface names: RNDIS and NCM both show up as usbN on most kernels
        val USB_TETHER_INTERFACE = Regex("(usb|rndis|ncm)\\d+")

        const val POLL_INTERVAL_MS = 1_000L
    }

    // One thread for state changes, also keeps the blocking JmDNS/Netty calls off the
    // main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineName(TAG))

    /**
     * The tether interface's addresses. IPv6 is the interface's link-local address: answering
     * AAAA queries with it (and serving on it) keeps resolvers from waiting out a missing AAAA
     * answer, which made every `.local` lookup take 5s on macOS.
     */
    private data class TetherAddresses(val ipv4: Inet4Address, val ipv6: Inet6Address?)

    // Write on [scope]'s thread, read from any thread via ipv4Address.
    @Volatile private var bound: TetherAddresses? = null

    /** The tether interface's IPv4 address while serving, else null. */
    val ipv4Address: String? get() = bound?.ipv4?.hostAddress
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val mdns = mutableListOf<JmDNS>()

    fun start() {
        scope.launch {
            try {
                while (isActive) {
                    refresh()
                    delay(POLL_INTERVAL_MS)
                }
            } finally {
                withContext(NonCancellable) { teardown() }
            }
        }
    }

    /** Stops polling and serving; returns once teardown has finished and the address is released. */
    suspend fun stop() = scope.coroutineContext.job.cancelAndJoin()

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
        val closing = mdns.toList()
        mdns.clear()
        if (closing.isNotEmpty()) {
            // these can take a while, don't block a new bring-up
            thread(isDaemon = true, name = "$TAG-mdns-close") {
                closing.forEach { runCatching { it.unregisterAllServices(); it.close() } }
            }
        }
        // by the time we get here the tether link is usually gone, don't worry about live connections
        server?.let { runCatching { it.stop(0, 1_000, TimeUnit.MILLISECONDS) } }
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
