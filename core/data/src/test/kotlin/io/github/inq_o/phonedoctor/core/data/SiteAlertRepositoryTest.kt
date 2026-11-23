package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.database.SiteAlertEntity
import io.github.inq_o.phonedoctor.core.database.SiteDailyCount
import io.github.inq_o.phonedoctor.core.database.SiteNotificationDao
import io.github.inq_o.phonedoctor.core.database.SiteNotificationEntity
import io.github.inq_o.phonedoctor.core.model.SiteAlertStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SiteAlertRepositoryTest {
    private val dao = FakeSiteDao()
    private val repo = DefaultSiteAlertRepository(dao).also {
        it.zone = ZoneOffset.UTC
        it.now = { T0 }
    }
    private val channel = "web:https://ads.example.com;123"

    private suspend fun post(text: String = "새 소식", channelId: String = channel, at: Long = T0, canNotify: Boolean = true) =
        repo.record("com.android.chrome", channelId, text, at, canNotify)

    @Test
    fun `사이트 주소는 채널 ID에서만 뽑는다`() {
        assertEquals("https://ads.example.com", SiteAdPolicy.siteOf("web:https://ads.example.com;123"))
        assertEquals("http://old.example", SiteAdPolicy.siteOf("web:http://old.example;1"))
        assertNull(SiteAdPolicy.siteOf("downloads"))
        assertNull(SiteAdPolicy.siteOf("web:;1"))
        assertNull(SiteAdPolicy.siteOf("web:https://;1"))
        assertNull(SiteAdPolicy.siteOf(null))
    }

    @Test
    fun `사이트 채널이 아니면 기록하지 않는다`() = runTest {
        assertNull(post(channelId = "downloads"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `하루 다섯 번이면 한 번만 묻는다`() = runTest {
        repeat(4) { assertNull(post()) }
        val ask = post() as SiteNotice.Ask
        assertEquals("ads.example.com", ask.alert.displayName)
        assertEquals(5, ask.alert.count)
        assertNull(post())
        // 문구는 저장하지 않는다
        assertTrue(dao.rows.none { it.toString().contains("새 소식") })
    }

    @Test
    fun `광고 문구가 두 번이면 바로 묻는다`() = runTest {
        assertNull(post("휴대폰이 바이러스에 감염됐어요"))
        assertTrue(post("배터리 정리하세요") is SiteNotice.Ask)
    }

    @Test
    fun `괜찮은 사이트는 다시 묻지 않는다`() = runTest {
        repeat(5) { post() }
        repo.allow("https://ads.example.com")
        repeat(10) { assertNull(post()) }
    }

    @Test
    fun `끄는 화면을 연 뒤에 또 오면 아직 켜져 있다고 한 번 알린다`() = runTest {
        repeat(5) { post() }
        repo.markMuted("https://ads.example.com")
        // 연 시각 전에 온 알림(늦게 도착)과 스위치를 끄는 동안 온 알림은 세지 않는다
        assertNull(post(at = T0 - 1))
        assertNull(post(at = T0 + SiteAdPolicy.MUTE_GRACE_MS))
        val still = post(at = T0 + SiteAdPolicy.MUTE_GRACE_MS + 1) as SiteNotice.StillOn
        assertEquals(SiteAlertStatus.STILL_ON, still.alert.status)
        assertNull(post(at = T0 + SiteAdPolicy.MUTE_GRACE_MS + 2))
    }

    @Test
    fun `Chrome이 채널을 새로 만들면 끄는 화면은 새 채널로 연다`() = runTest {
        repeat(5) { post() }
        repo.markMuted("https://ads.example.com")
        val newChannel = "web:https://ads.example.com;456"
        val still = post(channelId = newChannel, at = T0 + SiteAdPolicy.MUTE_GRACE_MS + 1) as SiteNotice.StillOn
        assertEquals(newChannel, still.alert.channelId)
    }

    @Test
    fun `아직 켜져 있어도 다시 끄는 화면을 열면 다시 본다`() = runTest {
        repeat(5) { post() }
        repo.markMuted("https://ads.example.com")
        assertTrue(post(at = T0 + SiteAdPolicy.MUTE_GRACE_MS + 1) is SiteNotice.StillOn)
        repo.markMuted("https://ads.example.com")
        assertNull(post(at = T0 + SiteAdPolicy.MUTE_GRACE_MS))
    }

    @Test
    fun `알림을 띄울 수 없으면 물은 것으로 적지 않고 다음에 묻는다`() = runTest {
        repeat(5) { assertNull(post(canNotify = false)) }
        assertTrue(post() is SiteNotice.Ask)
    }

    @Test
    fun `오래된 기록은 알림이 끊겨도 정리한다`() = runTest {
        dao.rows += SiteNotificationEntity(0, "https://ads.example.com", "com.android.chrome", channel, T0 - 31 * 86_400_000L, "2023-10-14", false)
        dao.rows += SiteNotificationEntity(0, "https://ads.example.com", "com.android.chrome", channel, T0 - 29 * 86_400_000L, "2023-10-16", false)
        repo.prune()
        assertEquals(1, dao.rows.size)
        dao.rows.clear()
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `광고 문구 판정`() {
        assertTrue(SiteAdPolicy.hasAdWords("축하합니다! 당첨되셨어요"))
        assertFalse(SiteAdPolicy.hasAdWords("오늘의 날씨"))
        assertFalse(SiteAdPolicy.isAdSender(4, 1))
        assertTrue(SiteAdPolicy.isAdSender(5, 0))
        assertTrue(SiteAdPolicy.isAdSender(2, 2))
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}

private class FakeSiteDao : SiteNotificationDao {
    val rows = mutableListOf<SiteNotificationEntity>()
    private val alerts = mutableMapOf<String, SiteAlertEntity>()

    override suspend fun insert(row: SiteNotificationEntity) {
        rows += row
    }

    override suspend fun dailyCount(site: String, date: String): SiteDailyCount? {
        val day = rows.filter { it.site == site && it.localDate == date }
        return if (day.isEmpty()) null else SiteDailyCount(site, date, day.size, day.count { it.adWords })
    }

    override suspend fun alert(site: String) = alerts[site]
    override suspend fun putAlert(alert: SiteAlertEntity) {
        alerts[alert.site] = alert
    }
    override fun observeAlerts(): Flow<List<SiteAlertEntity>> = flowOf(alerts.values.toList())
    override fun observeAlert(site: String): Flow<SiteAlertEntity?> = flowOf(alerts[site])
    override suspend fun deleteOlderThan(before: Long) {
        rows.removeAll { it.postedAt < before }
    }
}
