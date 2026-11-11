package io.github.inq_o.phonedoctor.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.model.Josa
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import java.time.LocalDate
import javax.inject.Inject

interface Notifier {
    /** 알림을 띄울 수 있는 상태인지 (권한·앱 알림 설정) */
    fun canNotify(): Boolean

    /** "확인이 필요한 앱이 있어요" 알림. 탭하면 그 앱 상세 화면이 열린다 */
    fun notifyAlert(alert: SymptomAlert)

    fun cancelAlert(alertId: Long)
}

class SystemNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : Notifier {
    override fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    override fun notifyAlert(alert: SymptomAlert) {
        if (!canNotify()) return
        ensureChannel()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.putExtra(EXTRA_ALERT_ID, alert.id)
        val pending = PendingIntent.getActivity(
            context, alert.id.toInt(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subject = Josa.iGa(alert.label)
        val date = runCatching { LocalDate.parse(alert.date) }.getOrNull()
        // 근거 날짜가 오늘이 아니면 "오늘" 대신 날짜를 적는다 (ux-flows.md "오늘" 표기 규칙)
        val text = if (date == null || date == LocalDate.now()) {
            context.getString(R.string.core_notifications_alert_text_today, subject, alert.count)
        } else {
            context.getString(R.string.core_notifications_alert_text_date, subject, date.monthValue, date.dayOfMonth, alert.count)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.core_notifications_ic_alert)
            .setContentTitle(context.getString(R.string.core_notifications_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(alert.id.toInt(), notification)
        } catch (e: SecurityException) {
            // 확인 직후 권한이 꺼진 경우. 홈 화면에는 그대로 보인다
        }
    }

    override fun cancelAlert(alertId: Long) {
        NotificationManagerCompat.from(context).cancel(alertId.toInt())
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.core_notifications_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.core_notifications_channel_alerts_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_ALERT_ID = "io.github.inq_o.phonedoctor.extra.ALERT_ID"
        private const val CHANNEL_ALERTS = "alerts"
    }
}
