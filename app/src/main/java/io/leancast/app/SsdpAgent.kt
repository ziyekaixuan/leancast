package io.leancast.app

import android.util.Log
import io.leancast.domain.ssdp.SsdpIdentity
import io.leancast.domain.ssdp.SsdpInbound
import io.leancast.domain.ssdp.SsdpMessageParser
import io.leancast.domain.ssdp.SsdpResponder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * SSDP network agent: answers M-SEARCHes on the SSDP multicast group and
 * announces the renderer with NOTIFY ssdp:alive (repeated) and ssdp:byebye
 * on stop. Modelled on castbay's SsdpServer:
 *
 * - the multicast group is joined on EVERY up, multicast-capable interface,
 *   not a single "preferred" one: on multi-interface TVs (Wi-Fi + Wi-Fi
 *   Direct + Ethernet) one pinned interface can be the wrong one, leaving
 *   the receiver undiscoverable until the app is opened once;
 * - announcements are re-evaluated per interface on every round (a burst,
 *   then every minute), each carrying that interface's own LOCATION;
 * - M-SEARCH replies carry the address the route to the searcher uses, so
 *   the LOCATION is always reachable from the phone asking;
 * - if port 1900 is already taken (the TV's own DLNA service), searches go
 *   unanswered but the announcements still go out.
 *
 * TVs often run their own DLNA service on port 1900. Sockets that all allow
 * address reuse share it; if one doesn't, [start] still announces the
 * renderer, but searches go unanswered and control points only find it from
 * the periodic announcements.
 */
class SsdpAgent(
    private val identity: SsdpIdentity,
    private val locationFor: (InetAddress) -> String,
    private val responder: SsdpResponder = SsdpResponder(identity),
) {
    @Volatile private var running = false
    private var listener: MulticastSocket? = null
    private var sender: MulticastSocket? = null
    private var listenThread: Thread? = null
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /** Whether M-SEARCH requests can be received (port 1900 could be bound). */
    var answersSearches = false
        private set

    fun start() {
        if (running) return
        running = true

        sender = MulticastSocket().apply { timeToLive = 4 }

        answersSearches = try {
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            val group = InetSocketAddress(InetAddress.getByName(GROUP), PORT)
            interfaces().forEach { (network, _) ->
                runCatching { socket.joinGroup(group, network) }
                    .onFailure { Log.w(TAG, "Could not join SSDP group on ${network.name}", it) }
            }
            listener = socket
            listenThread = Thread({ listen(socket) }, "ssdp-listener").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: SocketException) {
            Log.w(TAG, "SSDP port $PORT is taken by another app; relying on announcements only", error)
            false
        }

        Log.i(TAG, "SSDP started (answers searches: $answersSearches)")

        // A quick burst so control points already searching see us, then a steady pace.
        repeat(3) { burst ->
            scheduler.schedule({ announceAlive() }, burst * 1000L, TimeUnit.MILLISECONDS)
        }
        scheduler.scheduleAtFixedRate(
            { announceAlive() },
            ANNOUNCE_INTERVAL_MS,
            ANNOUNCE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { announceByebye() }
        scheduler.shutdownNow()
        listenThread?.interrupt()
        runCatching { listener?.close() }
        runCatching { sender?.close() }
        listener = null
        sender = null
    }

    private fun listen(socket: MulticastSocket) {
        val buf = ByteArray(4096)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                if (packet.address.isLoopbackAddress) continue
                val copy = packet.data.copyOf(packet.length)
                when (val msg = SsdpMessageParser.parse(copy, packet.address, packet.port)) {
                    is SsdpInbound.MSearch -> {
                        Log.d(TAG, "M-SEARCH ${msg.st} from ${packet.address.hostAddress}")
                        scheduleResponses(msg)
                    }
                    is SsdpInbound.Ignored -> Unit
                }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun scheduleResponses(msg: SsdpInbound.MSearch) {
        // Answer with the address the route to the searcher uses: the reply's
        // LOCATION is then reachable from the phone asking, whichever
        // interface it arrived on.
        val location = localAddressFor(msg.from)?.let(locationFor) ?: identity.location
        val responses = responder.responsesFor(msg, location)
        if (responses.isEmpty()) return
        val delayMs = responder.responseDelayMs(msg.mxSeconds)
        scheduler.schedule({
            runCatching {
                DatagramSocket().use { socket ->
                    val target = InetSocketAddress(msg.from, msg.fromPort)
                    responses.forEach { bytes ->
                        socket.send(DatagramPacket(bytes, bytes.size, target))
                    }
                }
            }.onFailure { Log.w(TAG, "Could not answer M-SEARCH from ${msg.from.hostAddress}", it) }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun announceAlive() {
        interfaces().forEach { (network, address) ->
            announceOn(network) { responder.aliveMessages(locationFor(address)) }
        }
    }

    private fun announceByebye() {
        interfaces().forEach { (network, _) ->
            announceOn(network) { responder.byebyeMessages() }
        }
    }

    private fun announceOn(network: NetworkInterface, messages: () -> List<ByteArray>) {
        val socket = sender ?: return
        val group = InetSocketAddress(InetAddress.getByName(GROUP), PORT)
        runCatching {
            socket.networkInterface = network
            messages().forEach { bytes ->
                socket.send(DatagramPacket(bytes, bytes.size, group))
            }
        }.onFailure { Log.w(TAG, "SSDP announcement failed on ${network.name}", it) }
    }

    /** Our address on the network that reaches [remote]. */
    private fun localAddressFor(remote: InetAddress): InetAddress? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(remote, PORT)
            socket.localAddress.takeUnless { it.isAnyLocalAddress }
        }
    }.getOrNull()

    /** Up, multicast-capable interfaces with an IPv4 address (SSDP here is IPv4 only). */
    private fun interfaces(): List<Pair<NetworkInterface, InetAddress>> =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .mapNotNull { network ->
                network.inetAddresses.toList().firstOrNull { it is Inet4Address }?.let { network to it }
            }

    companion object {
        private const val TAG = "LeanCast"
        const val GROUP = "239.255.255.250"
        const val PORT = 1900
        private const val ANNOUNCE_INTERVAL_MS = 60_000L
    }
}
