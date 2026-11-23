package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteNotificationFilterTest {
    private val filter = SiteNotificationFilter()
    private val site = "web:https://ads.example.com;1"

    @Test
    fun onlyChromeSiteChannelsCount() {
        assertFalse(filter.accepts("com.kakao.talk", site, 0, "k1"))
        assertFalse(filter.accepts("com.android.chrome", "downloads", 0, "k2"))
        assertFalse(filter.accepts("com.android.chrome", null, 0, "k3"))
        assertTrue(filter.accepts("com.android.chrome", site, 0, "k4"))
        assertTrue(filter.accepts("com.chrome.beta", site, 0, "k5"))
    }

    @Test
    fun summariesAndUpdatesAreNotCountedAgain() {
        assertFalse(filter.accepts("com.android.chrome", site, Notification.FLAG_GROUP_SUMMARY, "g"))
        assertTrue(filter.accepts("com.android.chrome", site, 0, "k"))
        // 떠 있는 알림의 갱신
        assertFalse(filter.accepts("com.android.chrome", site, 0, "k"))
        // 지운 뒤 같은 key로 새로 옴
        filter.removed("k")
        assertTrue(filter.accepts("com.android.chrome", site, 0, "k"))
    }

    @Test
    fun alreadyShownNotificationsAreNotCountedOnReconnect() {
        filter.reset(listOf("old"))
        assertFalse(filter.accepts("com.android.chrome", site, 0, "old"))
        assertTrue(filter.accepts("com.android.chrome", site, 0, "new"))
    }
}
