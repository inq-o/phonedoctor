package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import io.github.inq_o.phonedoctor.core.data.SiteAdPolicy
import io.github.inq_o.phonedoctor.core.data.SiteAlertRepository
import io.github.inq_o.phonedoctor.core.data.SiteNotice
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.feature.seniorsites.api.SiteAlertsFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Chrome 사이트 알림 살펴보기 (US10, FR-050). 사용자가 알림 접근을 허용해야 불린다.
 * Chrome이 아닌 앱(카카오톡·문자 등)의 알림은 내용을 읽지 않고 바로 버린다. 사이트 알림도 문구는 광고 문구 판정에만 쓰고 저장하지 않는다
 */
@AndroidEntryPoint
class SiteNotificationListener : NotificationListenerService() {
    @Inject
    lateinit var repository: SiteAlertRepository

    @Inject
    lateinit var notifier: Notifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!SiteAlertsFeature.ENABLED) return
        // 브라우저가 아니면 여기서 끝 (내용을 보지 않는다)
        if (sbn.packageName !in BROWSERS) return
        val notification = sbn.notification ?: return
        val channelId = notification.channelId ?: return
        // 사이트 알림 채널이 아니면(다운로드 진행 등) 버린다
        if (SiteAdPolicy.siteOf(channelId) == null) return
        val extras = notification.extras
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        val pkg = sbn.packageName
        val postedAt = sbn.postTime
        scope.launch {
            when (val notice = repository.record(pkg, channelId, text, postedAt)) {
                is SiteNotice.Ask -> notifier.notifySiteAds(notice.alert, stillOn = false)
                is SiteNotice.StillOn -> notifier.notifySiteAds(notice.alert, stillOn = true)
                null -> Unit
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** Chrome만 (삼성 인터넷은 채널 구조 확인 전, spec Q7) */
        val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
    }
}
