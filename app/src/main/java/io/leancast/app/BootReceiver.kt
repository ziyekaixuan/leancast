package io.leancast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver on boot only when the user enabled autostart. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // QUICKBOOT_POWERON is the boot broadcast some OEM firmwares send
        // instead of (or in addition to) BOOT_COMPLETED.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != ACTION_QUICKBOOT_POWERON
        ) return
        if (Settings(context).autostart) {
            ReceiverService.start(context)
        }
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
