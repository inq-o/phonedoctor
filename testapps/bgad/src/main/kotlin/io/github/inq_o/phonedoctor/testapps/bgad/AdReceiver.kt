package io.github.inq_o.phonedoctor.testapps.bgad

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** 알람마다 광고 1번을 띄우고 다음 알람을 건다. 띄운 시각이 정답(logcat `BGAD SHOW`). */
class AdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: return
        val left = intent.getIntExtra("left", 0)
        val intervalSec = intent.getIntExtra("intervalSec", 60)
        val holdSec = intent.getIntExtra("holdSec", 5)
        val seq = intent.getIntExtra("seq", 0)
        Log.i(TAG, "SHOW mode=$mode seq=$seq t=${System.currentTimeMillis()}")
        when (mode) {
            "saw" -> context.startActivity(AdActivity.intent(context, mode, seq, holdSec))
            "fsi" -> fullScreenIntent(context, seq, holdSec)
            "overlay" -> overlay(context, seq, holdSec)
            "notif" -> plainNotification(context, seq)
        }
        if (left > 1) schedule(context, mode, left - 1, intervalSec, holdSec, seq + 1)
    }

    private fun fullScreenIntent(context: Context, seq: Int, holdSec: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "광고", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            context, seq, AdActivity.intent(context, "fsi", seq, holdSec),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("bgad $seq")
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setTimeoutAfter(holdSec * 1000L)
            .build()
        nm.notify(seq, n)
    }

    /** 정상 알림(E2 알림 탭): 사용자가 눌러야만 NormalActivity가 열린다 */
    private fun plainNotification(context: Context, seq: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MSG, "메시지", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            context, seq, Intent(context, NormalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            1000 + seq,
            Notification.Builder(context, CHANNEL_MSG)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("메시지 $seq")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun overlay(context: Context, seq: Int, holdSec: Int) {
        val wm = context.getSystemService(WindowManager::class.java)
        val view = TextView(context).apply {
            text = "광고 (bgad overlay $seq)"
            textSize = 32f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(255, 200, 0))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )
        val pending = goAsync()
        wm.addView(view, lp)
        Handler(Looper.getMainLooper()).postDelayed({
            wm.removeView(view)
            pending.finish()
        }, holdSec * 1000L)
    }

    companion object {
        const val TAG = "BGAD"
        private const val CHANNEL = "ad"
        private const val CHANNEL_MSG = "msg"

        fun schedule(context: Context, mode: String, left: Int, intervalSec: Int, holdSec: Int, seq: Int) {
            val i = Intent(context, AdReceiver::class.java)
                .putExtra("mode", mode).putExtra("left", left).putExtra("intervalSec", intervalSec)
                .putExtra("holdSec", holdSec).putExtra("seq", seq)
            val pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalSec * 1000L,
                pi,
            )
            Log.i(TAG, "SCHEDULED mode=$mode seq=$seq inSec=$intervalSec left=$left")
        }
    }
}
