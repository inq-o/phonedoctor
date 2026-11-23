package io.github.inq_o.phonedoctor.feature.seniorsites.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Chrome 사이트 광고 알림 끄기 (US10) 켜짐 여부와 알림 접근 권한 */
object SiteAlertsFeature {
    /** 이 빌드에서 쓰는지. 출시 빌드는 개인정보처리방침에 알림 접근을 적기 전까지 끈다 */
    const val ENABLED: Boolean = BuildConfig.SITE_ALERTS_ENABLED

    /** 알림 리스너 서비스 클래스 이름 (impl 모듈). 설정의 이 앱 항목을 바로 열 때 쓴다 */
    const val LISTENER_CLASS = "io.github.inq_o.phonedoctor.feature.seniorsites.impl.SiteNotificationListener"

    /** 사용자가 알림 접근을 허용했는지 */
    fun isGranted(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** 알림 접근 설정 화면. 이 앱 항목을 바로 여는 것(Android 11+)과 목록을 여는 것 순서로 시도한다 */
    fun settingsIntents(context: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context.packageName, LISTENER_CLASS).flattenToString()),
            )
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}
