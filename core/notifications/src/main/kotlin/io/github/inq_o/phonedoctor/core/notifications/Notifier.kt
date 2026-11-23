package io.github.inq_o.phonedoctor.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    /** 가족이 연결을 요청함 (푸시 LINK_REQUESTED). 탭하면 연결 확인 화면(S3)이 열린다 */
    fun notifyLinkRequested(linkId: String, guardianName: String?)

    /** 상대가 연결을 끊음 (푸시 LINK_REVOKED). 탭하면 가족 연결 관리 화면(S5)이 열린다 */
    fun notifyLinkRevoked(linkId: String, name: String?)

    fun cancelLink(linkId: String)
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

    override fun notifyLinkRequested(linkId: String, guardianName: String?) {
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_LINK_ID, linkId)
        val text = context.getString(R.string.core_notifications_link_requested_text, nameOrFallback(guardianName))
        notifyLink(linkId, context.getString(R.string.core_notifications_link_requested_title), text, launch)
    }

    override fun notifyLinkRevoked(linkId: String, name: String?) {
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_OPEN_FAMILY, true)
        val text = context.getString(R.string.core_notifications_link_revoked_text, nameOrFallback(name))
        notifyLink(linkId, context.getString(R.string.core_notifications_link_revoked_title), text, launch)
    }

    override fun cancelLink(linkId: String) {
        NotificationManagerCompat.from(context).cancel(linkId, LINK_NOTIFICATION_ID)
    }

    private fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)

    private fun nameOrFallback(name: String?): String =
        name?.trim()?.ifEmpty { null } ?: context.getString(R.string.core_notifications_family_fallback)

    /** 연결 알림은 연결마다 하나. 같은 연결의 새 알림(요청 → 끊김)은 앞 알림을 바꾼다 */
    private fun notifyLink(linkId: String, title: String, text: String, launch: Intent) {
        if (!canNotify()) return
        ensureLinkChannel()
        // 요청 코드가 같으면 PendingIntent가 앞의 extra로 덮이므로 연결마다 다르게
        val pending = PendingIntent.getActivity(
            context, linkId.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_LINK)
            .setSmallIcon(R.drawable.core_notifications_ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(linkId, LINK_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 확인 직후 권한이 꺼진 경우. 홈 화면의 연결 요청 카드로도 보인다
        }
    }

    private fun ensureLinkChannel() {
        val channel = NotificationChannel(
            CHANNEL_LINK,
            context.getString(R.string.core_notifications_channel_link),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.core_notifications_channel_link_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        const val EXTRA_LINK_ID = "io.github.inq_o.phonedoctor.extra.LINK_ID"
        const val EXTRA_OPEN_FAMILY = "io.github.inq_o.phonedoctor.extra.OPEN_FAMILY"
        private const val CHANNEL_ALERTS = "alerts"

        // push-messages.md §4
        private const val CHANNEL_LINK = "link"

        /** 연결 알림은 tag(linkId)로 구분한다. tag 없는 요청 알림과는 ID가 같아도 겹치지 않는다 */
        private const val LINK_NOTIFICATION_ID = 1
    }
}
