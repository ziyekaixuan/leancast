package io.leancast.app

import android.util.Log
import io.leancast.domain.ssdp.SsdpIdentity
import io.leancast.domain.ssdp.SsdpInbound
import io.leancast.domain.ssdp.SsdpMessageParser
import io.leancast.domain.ssdp.SsdpResponder
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * SSDP network agent: joins the multicast group, answers M-SEARCHes with
 * MX-paced unicast replies, and multicasts alive/byebye notifications.
 */
class SsdpAgent(
    private val identity: SsdpIdentity,
    private val responder: SsdpResponder = SsdpResponder(identity),
) {
    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var listenThread: Thread? = null
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /**
     * Joins the multicast group and starts listening. [networkInterface] pins
     * the join (and outgoing packets) to the LAN interface: without it the
     * socket joins on whatever the system picks at boot — with the network
     * still down that is often loopback, so M-SEARCHes on the real LAN never
     * arrive and the receiver stays undiscoverable until the app is opened.
     */
    fun start(networkInterface: NetworkInterface? = null) {
        if (running) return
        running = true
        val group = InetAddress.getByName(GROUP)
        val sock = MulticastSocket(null)
        try {
            sock.apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                if (networkInterface != null) {
                    this.networkInterface = networkInterface
                    joinGroup(InetSocketAddress(group, PORT), networkInterface)
                } else {
                    joinGroup(group)
                }
            }
        } catch (e: Exception) {
            // Join failed (e.g. no network yet): don't leave a half-open
            // socket behind — the caller retries once the network is up.
            running = false
            runCatching { sock.close() }
            throw e
        }
        socket = sock
        Log.i(TAG, "SSDP listening on ${networkInterface?.name ?: "default"}:1900, location=${identity.location}")

        listenThread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    if (packet.address.isLoopbackAddress) continue
                    val copy = packet.data.copyOf(packet.length)
                    when (val msg = SsdpMessageParser.parse(copy, packet.address, packet.port)) {
                        is SsdpInbound.MSearch -> {
                            Log.d(TAG, "M-SEARCH from ${packet.address.hostAddress}:${packet.port}")
                            scheduleResponses(msg, sock)
                        }
                        is SsdpInbound.Ignored -> Unit
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }.apply {
            name = "ssdp-listener"
            isDaemon = true
            start()
        }

        // UPnP recommends several announcements at startup.
        repeat(3) { burst ->
            scheduler.schedule({
                multicast(responder.aliveMessages(), sock, group)
            }, burst * 350L, TimeUnit.MILLISECONDS)
        }
        // Periodic refresh at max-age/2.
        scheduler.scheduleAtFixedRate(
            { multicast(responder.aliveMessages(), sock, group) },
            (identity.maxAgeSeconds / 2).toLong(),
            (identity.maxAgeSeconds / 2).toLong(),
            TimeUnit.SECONDS,
        )
    }

    fun stop() {
        if (!running) return
        running = false
        socket?.let { sock ->
            runCatching {
                val group = InetAddress.getByName(GROUP)
                multicast(responder.byebyeMessages(), sock, group)
            }
        }
        scheduler.shutdownNow()
        listenThread?.interrupt()
        socket?.close()
        socket = null
    }

    private fun scheduleResponses(msg: SsdpInbound.MSearch, sock: MulticastSocket) {
        val responses = responder.responsesFor(msg)
        if (responses.isEmpty()) return
        val delayMs = responder.responseDelayMs(msg.mxSeconds)
        scheduler.schedule({
            responses.forEach { bytes ->
                runCatching {
                    val target = InetSocketAddress(msg.from, msg.fromPort)
                    sock.send(DatagramPacket(bytes, bytes.size, target))
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun multicast(messages: List<ByteArray>, sock: MulticastSocket, group: InetAddress) {
        messages.forEach { bytes ->
            runCatching {
                sock.send(DatagramPacket(bytes, bytes.size, group, PORT))
            }
        }
    }

    companion object {
        private const val TAG = "LeanCast"
        const val GROUP = "239.255.255.250"
        const val PORT = 1900
    }
}
