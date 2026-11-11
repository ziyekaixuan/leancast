package io.leancast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * Brings the receiver back when the network comes up or Wi-Fi changes.
 *
 * `CONNECTIVITY_ACTION` and the `WifiManager` broadcasts are implicit broadcasts:
 * since Android 7.0/8.0 they are no longer delivered to manifest-registered
 * receivers, so this one is registered dynamically by [ReceiverService] instead.
 * Boot is covered separately by [BootReceiver], which stays manifest-registered.
 *
 * The receiver only triggers a (re)start: if the service is already running,
 * [ReceiverService.onStartCommand] rebinds SSDP when the LAN address changed;
 * if it is not running, it only starts when the user enabled autostart.
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // Wi-Fi being switched off (or still off): nothing to bind to.
        if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION &&
            intent.getIntExtra(
                WifiManager.EXTRA_WIFI_STATE,
                WifiManager.WIFI_STATE_UNKNOWN,
            ) != WifiManager.WIFI_STATE_ENABLED
        ) return
        // Still connecting / no LAN address yet (DHCP lags the broadcast
        // burst): return WITHOUT consuming the debounce slot, so the next
        // broadcast — or the service's own bind-retry poll — can still act
        // once the address exists.
        if (Net.lanIpv4() == null) return
        // Wi-Fi connect fires a burst of broadcasts; act on the first only.
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerMs < DEBOUNCE_MS) return
        lastTriggerMs = now
        if (ReceiverService.isRunning || Settings(context).autostart) {
            ReceiverService.start(context)
        }
    }

    companion object {
        val ACTIONS = arrayOf(
            ConnectivityManager.CONNECTIVITY_ACTION,
            WifiManager.WIFI_STATE_CHANGED_ACTION,
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
        )
        private const val DEBOUNCE_MS = 1_500L
        private var lastTriggerMs = 0L

        fun intentFilter() = IntentFilter().apply { ACTIONS.forEach(::addAction) }
    }
}
