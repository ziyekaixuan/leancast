package io.leancast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.TransportState
import io.leancast.domain.cast.VolumeMath
import io.leancast.domain.ssdp.SsdpIdentity
import io.leancast.domain.upnp.LastChangeEvent
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapRouter
import io.leancast.domain.upnp.UpnpTime

/**
 * Foreground service that owns the whole receiver: multicast lock, SSDP agent,
 * HTTP control server, cast session, and the ExoPlayer-backed player port.
 */
class ReceiverService : Service() {

    private lateinit var settings: Settings
    private lateinit var session: CastSession
    private lateinit var playerPort: ExoPlayerPort
    private var server: ControlServer? = null
    private var ssdp: SsdpAgent? = null
    private lateinit var events: SubscriptionRegistry
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var audioManager: AudioManager
    /** LAN address the SSDP LOCATION was bound to; re-checked on every start. */
    private var boundIp: String? = null
    private var networkReceiver: NetworkChangeReceiver? = null
    private val bindRetryHandler = Handler(Looper.getMainLooper())
    private var bindRetries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Promote to foreground first: `startForegroundService` gives us only an
        // ~5 s window, so do it before the (slower) player/server wiring below.
        startForeground(NOTIFICATION_ID, buildNotification())
        settings = Settings(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        playerPort = ExoPlayerPort(this)
        session = CastSession(playerPort).also { s ->
            playerPort.session = s
            s.addListener(::onTransportStateChanged)
            s.setVolumeSink(::applyDeviceVolume)
            s.setVolume(initialVolumePercent())
        }

        CastBus.session = session
        CastBus.port = playerPort

        events = SubscriptionRegistry()
        startNetworkStack()
        registerNetworkReceiver()
        scheduleBindRetry()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Repeated start (e.g. from NetworkChangeReceiver after a Wi-Fi
        // reconnect): rebind SSDP when the LAN address changed, so the
        // receiver stays discoverable on the new network. Same address
        // means nothing to do — this also swallows broadcast bursts.
        if (::settings.isInitialized && boundIp != Net.lanIpv4()) {
            restartNetworkStack()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bindRetryHandler.removeCallbacks(bindRetryTask)
        runCatching { networkReceiver?.let(::unregisterReceiver) }
        networkReceiver = null
        isRunning = false
        cancelCastNotification()
        stopNetworkStack()
        events.shutdown()
        playerPort.release()
        CastBus.session = null
        CastBus.port = null
        CastBus.endpoint = ""
        super.onDestroy()
    }

    /**
     * (Re)binds everything that depends on the LAN address: HTTP control
     * server, SSDP agent (its LOCATION embeds the address) and the multicast
     * lock. The cast session and player are untouched, so an active cast
     * survives a network rebind.
     */
    private fun startNetworkStack() {
        val server = ControlServer.startWithFallback(
            preferredPort = PREFERRED_PORT,
            descriptionProvider = ::rendererDescription,
            router = SoapRouter(session),
            events = events,
            eventBody = ::eventBody,
            serverToken = serverToken(),
        )
        this.server = server

        val lanIp = Net.lanIpv4()
        boundIp = lanIp
        CastBus.endpoint = "${lanIp ?: "0.0.0.0"}:${server.boundPort}"

        // BOOT_COMPLETED fires before the network is up: with no LAN address
        // yet there is nothing valid to advertise, so skip SSDP entirely
        // instead of announcing LOCATION http://0.0.0.0:… and joining the
        // multicast group on the wrong interface. The network broadcast (or
        // the bind-retry poll below) rebinds once an address exists.
        ssdp = if (lanIp != null) {
            val identity = SsdpIdentity(
                uuid = settings.uuid(),
                location = "http://${lanIp}:${server.boundPort}/desc.xml",
                serverToken = serverToken(),
            )
            runCatching { SsdpAgent(identity).also { it.start(Net.lanInterface()) } }
                .getOrNull()
        } else {
            null
        }

        acquireMulticastLock()
    }

    private fun stopNetworkStack() {
        ssdp?.stop()
        ssdp = null
        server?.stop()
        server = null
        releaseMulticastLock()
    }

    private fun restartNetworkStack() {
        stopNetworkStack()
        startNetworkStack()
        scheduleBindRetry()
    }

    /**
     * Boot safety net: the network broadcasts normally trigger the rebind,
     * but on some firmwares the burst arrives before DHCP hands out an
     * address (and is then debounced away), leaving the receiver
     * undiscoverable until the app is opened once. Poll until the stack is
     * bound; the broadcasts remain the fast path.
     */
    private val bindRetryTask = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val ip = Net.lanIpv4()
            if (ip != null && (boundIp != ip || ssdp == null)) {
                // restartNetworkStack() re-arms this poll itself.
                restartNetworkStack()
                return
            }
            if ((boundIp == null || ssdp == null) && bindRetries < MAX_BIND_RETRIES) {
                bindRetries++
                bindRetryHandler.postDelayed(this, BIND_RETRY_DELAY_MS)
            }
        }
    }

    private fun scheduleBindRetry() {
        if (boundIp == null || ssdp == null) {
            bindRetries = 0
            bindRetryHandler.removeCallbacks(bindRetryTask)
            bindRetryHandler.postDelayed(bindRetryTask, BIND_RETRY_DELAY_MS)
        }
    }

    /**
     * Listens for connectivity / Wi-Fi changes while the service lives.
     * These broadcasts are not delivered to manifest-registered receivers
     * since Android 7.0/8.0, hence the dynamic registration here.
     */
    private fun registerNetworkReceiver() {
        val receiver = NetworkChangeReceiver()
        networkReceiver = receiver
        val filter = NetworkChangeReceiver.intentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun rendererDescription() = RendererDescription(
        friendlyName = settings.friendlyName(),
        uuid = settings.uuid(),
        manufacturer = getString(R.string.manufacturer),
        modelName = getString(R.string.model_name),
        baseUrl = "http://${CastBus.endpoint}",
    )

    private fun serverToken(): String =
        "Android/${Build.VERSION.RELEASE} UPnP/1.0 ${getString(R.string.model_name)}/1"

    private fun onTransportStateChanged(state: TransportState) {
        if (state == TransportState.TRANSITIONING) {
            // Surface the player when a cast starts.
            runCatching {
                startActivity(
                    Intent(this, PlayerActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
                )
            }
            // A direct startActivity from a background service is blocked on
            // Android 10+, which is why a cast right after boot never showed
            // video until the app had been opened once: post the cast-start
            // notification with a full-screen intent, which the system is
            // allowed to launch from the background.
            postCastNotification()
        }
        if (state == TransportState.STOPPED) {
            cancelCastNotification()
        }
        updateNotification(state)
        events.publish("avt", eventBody("avt"))
    }

    /** GENA `LastChange` payload for a service, reflecting the current session. */
    private fun eventBody(service: String): String = when (service) {
        "rcs" -> LastChangeEvent.renderingControl(session.volume, mute = false)
        else -> LastChangeEvent.avTransport(
            transportState = session.state.name,
            currentUri = session.currentUri,
            currentTrackDuration = UpnpTime.renderMsToHms(session.durationMs()),
            isLive = session.isLive(),
        )
    }

    private fun applyDeviceVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            VolumeMath.mapPercentToSteps(percent, max),
            0,
        )
        // Guarded: the initial volume is applied while onCreate is still wiring
        // up the eventing layer.
        if (::events.isInitialized) events.publish("rcs", eventBody("rcs"))
    }

    private fun initialVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return Math.round(current * 100.0 / max).toInt()
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("leancast-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun buildNotification(state: TransportState = TransportState.NO_MEDIA_PRESENT): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, state.name))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: TransportState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    /**
     * Posts the cast-start notification with a full-screen intent to the
     * player screen. High-importance channel: that is what lets the system
     * launch the activity immediately, even when the app has never been
     * opened since boot. The intent targets singleTop PlayerActivity, so
     * firing it while the player is already up is a harmless re-delivery.
     */
    private fun postCastNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CAST_CHANNEL_ID,
                    getString(R.string.cast_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CAST_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).apply {
                // Builder has setPriority() but no getPriority(), so Kotlin
                // cannot use property syntax here — call the setter directly.
                setPriority(Notification.PRIORITY_HIGH)
            }
        }
        val title = if (::session.isInitialized) {
            session.currentTitle?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.cast_notif_title))
            .setContentText(
                title?.let { getString(R.string.cast_notif_text, it) }
                    ?: getString(R.string.app_name),
            )
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(CAST_NOTIFICATION_ID, notification)
    }

    private fun cancelCastNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(CAST_NOTIFICATION_ID)
    }

    companion object {
        private const val CHANNEL_ID = "receiver"
        private const val CAST_CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val CAST_NOTIFICATION_ID = 2
        private const val PREFERRED_PORT = 49_152
        private const val BIND_RETRY_DELAY_MS = 3_000L
        private const val MAX_BIND_RETRIES = 40

        /** True while the service instance is alive; read by the receivers. */
        @Volatile
        var isRunning = false
            private set

        /**
         * Starts the receiver. Uses [Context.startForegroundService] on API 26+ so the
         * call is legal from a background context (e.g. [BootReceiver] after boot) —
         * a plain `startService` there throws `IllegalStateException` on Android 8+.
         * On Android 12+ the system may still refuse a background start
         * ([android.app.ForegroundServiceStartNotAllowedException]); that is
         * swallowed here because there is nothing useful to do about it — the
         * service will come up on the next trigger (e.g. opening the app).
         */
        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
