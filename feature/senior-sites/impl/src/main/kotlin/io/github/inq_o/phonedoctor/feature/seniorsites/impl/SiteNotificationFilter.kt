package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import android.app.Notification
import io.github.inq_o.phonedoctor.core.data.SiteAdPolicy

/**
 * 셀 만한 사이트 알림인지 고른다 (US10). 내용을 읽기 전에 패키지·채널·종류만 보고 정한다.
 * 이미 떠 있는 알림의 갱신과 시스템이 만든 묶음 요약은 세지 않는다 (몇 번 보냈는지 부풀지 않게).
 * 알림이 지워진 뒤 같은 key로 다시 오면 새 알림으로 센다
 */
internal class SiteNotificationFilter {
    /** 지금 떠 있는 사이트 알림의 key */
    private val active = mutableSetOf<String>()

    fun accepts(packageName: String, channelId: String?, flags: Int, key: String): Boolean {
        // 브라우저가 아니면 여기서 끝 (내용을 보지 않는다)
        if (packageName !in BROWSERS) return false
        // 사이트 알림 채널이 아니면(다운로드 진행 등) 버린다
        if (SiteAdPolicy.siteOf(channelId) == null) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return synchronized(active) { active.add(key) }
    }

    fun removed(key: String) {
        synchronized(active) { active.remove(key) }
    }

    /** 리스너가 (다시) 연결됨: 지금 떠 있는 알림은 이미 센 것으로 둔다 */
    fun reset(activeKeys: Collection<String>) {
        synchronized(active) {
            active.clear()
            active.addAll(activeKeys)
        }
    }

    companion object {
        /** Chrome만 (삼성 인터넷은 채널 구조 확인 전, spec Q7) */
        val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
    }
}
