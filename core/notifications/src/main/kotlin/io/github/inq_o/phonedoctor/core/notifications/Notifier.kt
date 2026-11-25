package io.github.inq_o.phonedoctor.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.Josa
import io.github.inq_o.phonedoctor.core.model.PhoneName
import io.github.inq_o.phonedoctor.core.model.SiteAlert
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import java.time.LocalDate
import javax.inject.Inject

interface Notifier {
    /** 알림을 띄울 수 있는 상태인지 (권한·앱 알림 설정) */
    fun canNotify(): Boolean

    /** "확인이 필요한 앱이 있어요" 알림. 탭하면 그 앱 상세 화면이 열린다 */
    fun notifyAlert(alert: SymptomAlert)

    fun cancelAlert(alertId: Long)

    /**
     * 보호자가 답함 (푸시 DECISION_MADE, F1 13~14). 지우자고 했으면 "민지님이 ○○을 지우자고 했어요"로 앞의 알림을 바꾸고(탭하면 S2),
     * 괜찮다고 했으면 새로 알리지 않고 떠 있는 알림만 "괜찮다고 했어요"로 바꾼다 (ux-flows F1 끝 주석)
     */
    fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?)

    /**
     * 시니어가 시스템 삭제 창에서 취소한 지 하루 뒤 한 번 (spec US5 시나리오 4): "민지님이 지우자고 한 ○○이 아직 있어요".
     * 탭하면 S2가 열려 다시 지울 수 있다
     */
    fun notifyStillInstalled(alert: SymptomAlert) = Unit

    /** 가족이 연결을 요청함 (푸시 LINK_REQUESTED). 탭하면 연결 확인 화면(S3)이 열린다 */
    fun notifyLinkRequested(linkId: String, guardianName: String?)

    /** (보호자) 시니어가 연결을 받아들임 (푸시 LINK_ACTIVATED). 탭하면 보호자 홈(G1)이 열린다 */
    fun notifyLinkActivated(linkId: String, phone: PhoneName)

    /**
     * 상대가 연결을 끊음 (푸시 LINK_REVOKED). 탭하면 연결 목록(시니어 S5, 보호자 G1)이 열린다.
     * 보호자가 끊었으면 [guardianName](시니어가 받음), 시니어가 끊었으면 [phone](보호자가 받음). 기기 삭제로 끊겼으면 둘 다 null일 수 있다
     */
    fun notifyLinkRevoked(linkId: String, guardianName: String?, phone: PhoneName?)

    fun cancelLink(linkId: String)

    /** (보호자) 시니어 폰의 검사 보고가 이틀째 없음 (푸시 CONTACT_LOST, ux-flows F6). 탭하면 보호자 홈(G1)이 열린다 */
    fun notifyContactLost(linkId: String, phone: PhoneName)

    /** (보호자) 요청 알림 (ALERT_CREATED·REMINDER·NOTICE). 탭하면 요청 상세(G2)가 열린다 */
    fun notifyGuardianAlert(message: GuardianAlertMessage)

    /** (보호자) 시니어 폰에서 처리가 끝남 (ALERT_RESOLVED, [outcome]은 openapi OutcomeType). 탭하면 G2 */
    fun notifyAlertResolved(alertId: String, outcome: String?, phone: PhoneName, appLabel: String?)

    /** (보호자) 요청을 화면으로 봤거나 답함 */
    fun cancelGuardianAlert(alertId: String)

    /**
     * (시니어) Chrome 사이트가 광고 알림을 보냄 (US10): "○○ 사이트가 광고 알림을 보내고 있어요. 끌까요?".
     * [stillOn]이면 끄는 화면을 연 뒤에도 옴. 탭하면 그 사이트 알림 설정을 바로 연다 (FR-052)
     */
    fun notifySiteAds(alert: SiteAlert, stillOn: Boolean)

    fun cancelSite(site: String)
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
        launch.data = notificationUri("alert", alert.id.toString())
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

    override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) {
        if (!canNotify()) return
        val name = nameOrFallback(guardianName)
        val manager = NotificationManagerCompat.from(context)
        val text = when (decision) {
            FamilyDecision.RECOMMEND_UNINSTALL -> context.getString(R.string.core_notifications_decision_uninstall_text, name, Josa.eulReul(alert.label))
            FamilyDecision.ALLOW -> {
                val showing = context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == alert.id.toInt() && it.tag == null }
                if (!showing) return
                context.getString(R.string.core_notifications_decision_allow_text, name, Josa.eunNeun(alert.label))
            }
        }
        ensureChannel()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.putExtra(EXTRA_ALERT_ID, alert.id)
        launch.data = notificationUri("decision", alert.id.toString())
        val pending = PendingIntent.getActivity(
            context, alert.id.toInt(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val uninstall = decision == FamilyDecision.RECOMMEND_UNINSTALL
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.core_notifications_ic_alert)
            .setContentTitle(
                context.getString(if (uninstall) R.string.core_notifications_decision_uninstall_title else R.string.core_notifications_decision_allow_title),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (uninstall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            // 괜찮다는 소식은 소리 없이 앞 알림만 바꾼다
            .setOnlyAlertOnce(!uninstall)
            .setSilent(!uninstall)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(alert.id.toInt(), notification)
        } catch (e: SecurityException) {
            // 확인 직후 권한이 꺼진 경우. 앱 상세 화면에는 그대로 보인다
        }
    }

    override fun notifyStillInstalled(alert: SymptomAlert) {
        if (!canNotify()) return
        ensureChannel()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.putExtra(EXTRA_ALERT_ID, alert.id)
        launch.data = notificationUri("nudge", alert.id.toString())
        val pending = PendingIntent.getActivity(
            context, alert.id.toInt(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.core_notifications_nudge_text, nameOrFallback(alert.family?.guardianName), Josa.iGa(alert.label))
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.core_notifications_ic_alert)
            .setContentTitle(context.getString(R.string.core_notifications_nudge_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(alert.id.toInt(), notification)
        } catch (e: SecurityException) {
            // 확인 직후 권한이 꺼진 경우
        }
    }

    override fun cancelAlert(alertId: Long) {
        NotificationManagerCompat.from(context).cancel(alertId.toInt())
    }

    override fun notifyLinkRequested(linkId: String, guardianName: String?) {
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_LINK_ID, linkId)
        launch.data = notificationUri("link-requested", linkId)
        val text = context.getString(R.string.core_notifications_link_requested_text, nameOrFallback(guardianName))
        notifyLink(linkId, context.getString(R.string.core_notifications_link_requested_title), text, launch)
    }

    override fun notifyLinkActivated(linkId: String, phone: PhoneName) {
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_OPEN_FAMILY, true)
        launch.data = notificationUri("link-activated", linkId)
        notifyLink(
            linkId,
            context.getString(R.string.core_notifications_link_activated_title, phoneLabel(phone)),
            context.getString(R.string.core_notifications_link_activated_text),
            launch,
        )
    }

    override fun notifyLinkRevoked(linkId: String, guardianName: String?, phone: PhoneName?) {
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_OPEN_FAMILY, true)
        launch.data = notificationUri("link-revoked", linkId)
        val guardian = guardianName?.trim()?.ifEmpty { null }
        when {
            // (시니어가 받음) 보호자가 끊음
            guardian != null -> notifyLink(
                linkId,
                context.getString(R.string.core_notifications_link_revoked_title),
                context.getString(R.string.core_notifications_link_revoked_text, guardian),
                launch,
            )
            // (보호자가 받음) 시니어가 끊었거나 요청을 거절함: 같은 푸시라 "끊겼어요" 대신 지금 상태를 말한다
            phone != null -> notifyLink(
                linkId,
                context.getString(R.string.core_notifications_link_revoked_by_senior_title, phoneLabel(phone)),
                context.getString(R.string.core_notifications_link_revoked_by_senior_text),
                launch,
            )
            else -> notifyLink(
                linkId,
                context.getString(R.string.core_notifications_link_revoked_title),
                context.getString(R.string.core_notifications_link_revoked_unknown_text),
                launch,
            )
        }
    }

    override fun notifyContactLost(linkId: String, phone: PhoneName) {
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_OPEN_FAMILY, true)
        launch.data = notificationUri("contact-lost", linkId)
        notifyLink(
            linkId,
            context.getString(R.string.core_notifications_contact_lost_title, phoneLabel(phone)),
            context.getString(R.string.core_notifications_contact_lost_text),
            launch,
        )
    }

    // 보호자 문구는 공공·기업 앱처럼 짧게: 제목 "{폰} 확인 필요", 본문 "{앱}, 오늘 광고 12회" (ux-flows §6). 조사가 붙지 않는다
    override fun notifyGuardianAlert(message: GuardianAlertMessage) {
        val phone = phoneLabel(message.phone)
        val app = message.appLabel?.trim()?.ifEmpty { null } ?: context.getString(R.string.core_notifications_app_fallback)
        val title: String
        val text: String
        when (message.kind) {
            GuardianAlertKind.REMINDER -> {
                title = context.getString(R.string.core_notifications_guardian_reminder_title)
                text = context.getString(R.string.core_notifications_guardian_reminder_text, phone, app)
            }
            GuardianAlertKind.CREATED, GuardianAlertKind.NOTICE -> {
                val allowed = message.kind == GuardianAlertKind.NOTICE
                title = context.getString(
                    if (allowed) R.string.core_notifications_guardian_notice_title else R.string.core_notifications_guardian_alert_title,
                    phone,
                )
                val count = message.symptomCount
                val date = message.symptomDate
                text = when {
                    // 설치 시점 판정만 있으면 횟수가 없다
                    count == null -> context.getString(R.string.core_notifications_guardian_installed_text, app)
                    date == null || date == LocalDate.now() -> context.getString(R.string.core_notifications_guardian_symptom_today, app, count)
                    else -> context.getString(R.string.core_notifications_guardian_symptom_date, app, date.monthValue, date.dayOfMonth, count)
                }
            }
        }
        val channel = if (message.kind == GuardianAlertKind.NOTICE) CHANNEL_NOTICE else CHANNEL_ALERTS
        notifyRequest(message.alertId, "request-${message.kind.name.lowercase()}", channel, title, text, high = message.kind != GuardianAlertKind.NOTICE)
    }

    override fun notifyAlertResolved(alertId: String, outcome: String?, phone: PhoneName, appLabel: String?) {
        val app = appLabel?.trim()?.ifEmpty { null } ?: context.getString(R.string.core_notifications_app_fallback)
        val text = context.getString(
            when (outcome) {
                "uninstalled" -> R.string.core_notifications_resolved_uninstalled
                "declined" -> R.string.core_notifications_resolved_declined
                "already_removed" -> R.string.core_notifications_resolved_already_removed
                "allowed" -> R.string.core_notifications_resolved_allowed
                "suspended" -> R.string.core_notifications_resolved_suspended
                else -> R.string.core_notifications_resolved_other
            },
            app,
        )
        notifyRequest(
            alertId, "request-resolved", CHANNEL_RESULT,
            context.getString(R.string.core_notifications_resolved_title, phoneLabel(phone)), text, high = false,
        )
    }

    override fun cancelGuardianAlert(alertId: String) {
        NotificationManagerCompat.from(context).cancel(requestTag(alertId), REQUEST_NOTIFICATION_ID)
    }

    /** 요청 알림은 요청마다 하나 (새 소식이 앞 알림을 바꾼다). 시니어 쪽 요청 알림(숫자 ID)과 겹치지 않게 tag로 구분 */
    private fun notifyRequest(alertId: String, kind: String, channel: String, title: String, text: String, high: Boolean) {
        if (!canNotify()) return
        ensureGuardianChannels()
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_REQUEST_ID, alertId)
        launch.data = notificationUri(kind, alertId)
        val pending = PendingIntent.getActivity(
            context, requestTag(alertId).hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.core_notifications_ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(requestTag(alertId), REQUEST_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 권한이 막 꺼진 경우. 보호자 홈의 요청 목록에는 그대로 보인다
        }
    }

    private fun requestTag(alertId: String) = "request:$alertId"

    private fun ensureGuardianChannels() {
        ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTICE, context.getString(R.string.core_notifications_channel_notice), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.core_notifications_channel_notice_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, context.getString(R.string.core_notifications_channel_result), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.core_notifications_channel_result_description) },
        )
    }

    override fun notifySiteAds(alert: SiteAlert, stillOn: Boolean) {
        if (!canNotify()) return
        ensureChannel()
        val launch = launchIntent() ?: return
        launch.putExtra(EXTRA_SITE, alert.site)
        // 알림마다 다른 주소: 화면이 이 주소로 "설정을 바로 열기"를 한 번만 한다
        launch.data = notificationUri(if (stillOn) "site-still-on" else "site-ads", alert.site).buildUpon()
            .appendQueryParameter("t", System.currentTimeMillis().toString()).build()
        val pending = PendingIntent.getActivity(
            context, siteTag(alert.site).hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(
            if (stillOn) R.string.core_notifications_site_still_on_title else R.string.core_notifications_site_ads_title,
            alert.displayName,
        )
        val text = context.getString(if (stillOn) R.string.core_notifications_site_still_on_text else R.string.core_notifications_site_ads_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.core_notifications_ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(siteTag(alert.site), SITE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 권한이 막 꺼진 경우. 설정의 사이트 목록에는 보인다
        }
    }

    override fun cancelSite(site: String) {
        NotificationManagerCompat.from(context).cancel(siteTag(site), SITE_NOTIFICATION_ID)
    }

    private fun siteTag(site: String) = "site:$site"

    override fun cancelLink(linkId: String) {
        NotificationManagerCompat.from(context).cancel(linkId, LINK_NOTIFICATION_ID)
    }

    /**
     * 알림마다 다른 주소. PendingIntent는 요청 코드와 Intent(data 포함)가 같으면 하나로 묶여 extras가 덮이므로,
     * 요청 코드(해시)가 우연히 겹쳐도 다른 알림의 화면이 열리지 않게 한다. 화면은 이 주소로 같은 알림을 두 번 열지 않는다
     */
    private fun notificationUri(kind: String, id: String): Uri =
        Uri.Builder().scheme(URI_SCHEME).authority(kind).appendPath(id).build()

    private fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)

    private fun nameOrFallback(name: String?): String =
        name?.trim()?.ifEmpty { null } ?: context.getString(R.string.core_notifications_family_fallback)

    /** 상대 폰 이름 (ux-flows §2): "엄마 폰" / "김영희님 폰" / "연결한 폰". 역할 이름을 대신 넣지 않는다 */
    private fun phoneLabel(phone: PhoneName): String = when (phone) {
        is PhoneName.Chosen -> context.getString(R.string.core_notifications_phone_chosen, phone.name)
        is PhoneName.Own -> context.getString(R.string.core_notifications_phone_own, phone.name)
        PhoneName.Unnamed -> context.getString(R.string.core_notifications_phone_unnamed)
    }

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
        private const val URI_SCHEME = "phonedoctor-notification"
        const val EXTRA_LINK_ID = "io.github.inq_o.phonedoctor.extra.LINK_ID"
        const val EXTRA_OPEN_FAMILY = "io.github.inq_o.phonedoctor.extra.OPEN_FAMILY"

        /** (시니어) 광고 알림을 보내는 사이트 주소. 탭하면 그 사이트 화면이 알림 설정을 바로 연다 */
        const val EXTRA_SITE = "io.github.inq_o.phonedoctor.extra.SITE"

        /** (보호자) 서버 요청 ID. 탭하면 G2 */
        const val EXTRA_REQUEST_ID = "io.github.inq_o.phonedoctor.extra.REQUEST_ID"
        private const val CHANNEL_ALERTS = "alerts"

        // push-messages.md §4
        private const val CHANNEL_LINK = "link"
        private const val CHANNEL_NOTICE = "notice"
        private const val CHANNEL_RESULT = "result"

        /** 보호자 요청 알림은 tag(request:ID)로 구분한다 */
        private const val REQUEST_NOTIFICATION_ID = 2

        /** 사이트 알림은 tag(site:주소)로 구분한다 */
        private const val SITE_NOTIFICATION_ID = 3

        /** 연결 알림은 tag(linkId)로 구분한다. tag 없는 요청 알림과는 ID가 같아도 겹치지 않는다 */
        private const val LINK_NOTIFICATION_ID = 1
    }
}
