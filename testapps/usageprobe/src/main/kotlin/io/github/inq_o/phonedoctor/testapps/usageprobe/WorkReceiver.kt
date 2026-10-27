package io.github.inq_o.phonedoctor.testapps.usageprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * adb shell am broadcast -n io.github.inq_o.phonedoctor.testapps.usageprobe/.WorkReceiver --es action start|stop
 */
class WorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wm = WorkManager.getInstance(context)
        when (intent.getStringExtra("action")) {
            "start" -> wm.enqueueUniquePeriodicWork(
                "probe", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ProbeWorker>(15, TimeUnit.MINUTES).build(),
            )
            "stop" -> wm.cancelUniqueWork("probe")
        }
    }
}
