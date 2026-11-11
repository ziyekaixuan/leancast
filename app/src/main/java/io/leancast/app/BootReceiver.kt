package io.leancast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the receiver on boot only when the user enabled autostart.
 * Also listens for the clock being set: some firmwares sleep rather than
 * boot, and waking syncs the time (after castbay's BootReceiver).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // QUICKBOOT_POWERON is the boot broadcast some OEM firmwares send
        // instead of (or in addition to) BOOT_COMPLETED.
        if (intent.action !in START_ACTIONS) return
        // Make sure the persisted wake job exists even if the service start
        // below is refused: the job brings the receiver back once a network
        // is available.
        WakeJobService.schedule(context)
        if (Settings(context).autostart) {
            ReceiverService.start(context)
        }
    }

    companion object {
        // Declared first: START_ACTIONS below reads it, and companion
        // properties initialise in declaration order.
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

        val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            // Fast-boot variants some TV and phone vendors send instead.
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // A waking device syncs its clock.
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
