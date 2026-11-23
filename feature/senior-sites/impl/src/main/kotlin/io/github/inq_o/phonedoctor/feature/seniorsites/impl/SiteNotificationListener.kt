package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import io.github.inq_o.phonedoctor.core.data.SiteAlertRepository
import io.github.inq_o.phonedoctor.core.data.SiteNotice
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.feature.seniorsites.api.SiteAlertsFeature
import kotlinx.coroutines.CoroutineExceptionHandler
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

    // 기록이 실패해도(디스크 가득 참 등) 앱이 죽지 않게. 그 알림 하나는 세지 못한다
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "사이트 알림 기록 실패", e) })
    private val filter = SiteNotificationFilter()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!SiteAlertsFeature.ENABLED) return
        val notification = sbn.notification ?: return
        val channelId = notification.channelId
        if (!filter.accepts(sbn.packageName, channelId, notification.flags, sbn.key)) return
        val extras = notification.extras
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        val pkg = sbn.packageName
        val postedAt = sbn.postTime
        // 알림을 띄울 수 없으면(권한 꺼짐) 물은 것으로 적지 않는다: 켠 뒤 다음 알림에서 묻는다
        val canAsk = notifier.canNotify()
        scope.launch {
            when (val notice = repository.record(pkg, channelId!!, text, postedAt, canAsk)) {
                is SiteNotice.Ask -> notifier.notifySiteAds(notice.alert, stillOn = false)
                is SiteNotice.StillOn -> notifier.notifySiteAds(notice.alert, stillOn = true)
                null -> Unit
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        filter.removed(sbn.key)
    }

    override fun onListenerConnected() {
        // 이미 떠 있는 알림은 연결 전에 받은 것: 다시 세지 않는다
        val keys = try {
            activeNotifications?.map { it.key }.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        filter.reset(keys)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PhoneDoctorSites"
    }
}
