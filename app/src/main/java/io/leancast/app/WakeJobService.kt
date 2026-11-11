package io.leancast.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Brings the receiver back once the device has a network again, after the
 * system stopped it: a TV that sleeps instead of shutting down, or a service
 * the system killed, sends no boot broadcast — but a persisted job carries on
 * once the network is back. (After castbay's WakeJobService.)
 *
 * Unlike the service's own Handler poll and dynamic network receiver, this
 * job survives reboots (setPersisted) and process death: the system owns the
 * schedule, and NETWORK_TYPE_ANY means it only fires once a network exists —
 * exactly the "network is up now, start the receiver" trigger.
 */
class WakeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "wake job: network available, ensuring receiver is up")
        ReceiverService.start(this)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "LeanCast"
        private const val JOB_ID = 7000

        /** Schedules the job once; later calls leave it be. */
        fun schedule(context: Context) {
            // String lookup (not getSystemService(Class)) so this works on
            // API 21/22 too: the class-based lookup arrived in API 23.
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, WakeJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                // Survives a reboot too (RECEIVE_BOOT_COMPLETED).
                .setPersisted(true)
                .build()
            val result = scheduler.schedule(job)
            Log.i(TAG, "wake job scheduled: ${result == JobScheduler.RESULT_SUCCESS}")
        }
    }
}
