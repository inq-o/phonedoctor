package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.database.SiteAlertEntity
import io.github.inq_o.phonedoctor.core.database.SiteNotificationDao
import io.github.inq_o.phonedoctor.core.database.SiteNotificationEntity
import io.github.inq_o.phonedoctor.core.model.SiteAlert
import io.github.inq_o.phonedoctor.core.model.SiteAlertStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 사이트 알림 하나를 기록한 결과: 시니어에게 알릴 것 */
sealed interface SiteNotice {
    /** "○○ 사이트가 광고 알림을 보내고 있어요. 끌까요?" (사이트당 한 번) */
    data class Ask(val alert: SiteAlert) : SiteNotice

    /** 끄는 화면을 열었는데 또 옴: "아직 켜져 있어요" (한 번) */
    data class StillOn(val alert: SiteAlert) : SiteNotice
}

/**
 * Chrome 사이트 광고 알림 (US10, FR-050~053). 기록은 모두 이 폰 안에만 둔다 (FR-054: 기기 밖·보호자에게도 보내지 않음)
 */
interface SiteAlertRepository {
    /** 광고 알림을 보내는 사이트들, 최근에 물은 것부터 */
    val alerts: Flow<List<SiteAlert>>

    fun alert(site: String): Flow<SiteAlert?>

    /**
     * 브라우저 알림 하나를 기록한다. 사이트 알림 채널이 아니면 기록하지 않는다. [text]는 판정에만 쓰고 저장하지 않는다.
     * 시니어에게 알릴 것이 있으면 돌려준다. [canNotify]가 false면(알림 권한 꺼짐) 물은 것으로 적지 않아 다음 알림에서 다시 판정한다
     */
    suspend fun record(browserPackage: String, channelId: String, text: String, postedAt: Long, canNotify: Boolean = true): SiteNotice?

    /** 시니어가 그 사이트 알림 설정 화면에서 돌아옴 (돌아온 뒤에 오는 알림으로 스위치가 꺼졌는지 본다) */
    suspend fun markMuted(site: String)

    /** 30일 지난 알림 기록을 지운다 (알림이 끊겨도 기록이 남지 않게 주기 검사가 부른다) */
    suspend fun prune()

    /** 시니어가 괜찮은 사이트라고 함 (허용 목록) */
    suspend fun allow(site: String)
}

@Singleton
class DefaultSiteAlertRepository @Inject constructor(private val dao: SiteNotificationDao) : SiteAlertRepository {
    internal var now: () -> Long = System::currentTimeMillis
    internal var zone: ZoneId = ZoneId.systemDefault()

    /** 알림이 한꺼번에 와도 사이트당 한 번만 묻게 하나씩 */
    private val lock = Mutex()

    override val alerts: Flow<List<SiteAlert>> = dao.observeAlerts().map { list -> list.map { it.toModel() } }

    override fun alert(site: String): Flow<SiteAlert?> = dao.observeAlert(site).map { it?.toModel() }

    override suspend fun record(browserPackage: String, channelId: String, text: String, postedAt: Long, canNotify: Boolean): SiteNotice? {
        val site = SiteAdPolicy.siteOf(channelId) ?: return null
        val date = Instant.ofEpochMilli(postedAt).atZone(zone).toLocalDate().toString()
        return lock.withLock {
            dao.insert(SiteNotificationEntity(0, site, browserPackage, channelId, postedAt, date, SiteAdPolicy.hasAdWords(text)))
            dao.deleteOlderThan(now() - SymptomPolicy.RETENTION_DAYS * 86_400_000L)
            // 끈 사이트를 다시 허용하면 Chrome이 채널을 새로 만든다(`;숫자`가 바뀜): 끄는 화면을 열 때 지금 채널을 쓰게 늘 최신으로
            val stored = dao.alert(site)
            val existing = if (stored != null && (stored.channelId != channelId || stored.browserPackage != browserPackage)) {
                stored.copy(channelId = channelId, browserPackage = browserPackage).also { dao.putAlert(it) }
            } else {
                stored
            }
            when (existing?.status) {
                SiteAlertStatus.ALLOWED.name, SiteAlertStatus.STILL_ON.name, SiteAlertStatus.ASKED.name -> null
                SiteAlertStatus.MUTED.name -> {
                    // 끄는 화면을 연 뒤에 온 알림: 스위치가 아직 켜져 있다. 화면을 열고 스위치를 끄기까지 온 것은 세지 않는다
                    if (postedAt <= (existing.mutedAt ?: 0) + SiteAdPolicy.MUTE_GRACE_MS || !canNotify) return@withLock null
                    val still = existing.copy(status = SiteAlertStatus.STILL_ON.name)
                    dao.putAlert(still)
                    SiteNotice.StillOn(still.toModel())
                }
                else -> {
                    val day = dao.dailyCount(site, date) ?: return@withLock null
                    if (!SiteAdPolicy.isAdSender(day.n, day.adWords) || !canNotify) return@withLock null
                    val asked = SiteAlertEntity(site, browserPackage, channelId, SiteAlertStatus.ASKED.name, date, day.n, now())
                    dao.putAlert(asked)
                    SiteNotice.Ask(asked.toModel())
                }
            }
        }
    }

    override suspend fun markMuted(site: String) = lock.withLock {
        val existing = dao.alert(site) ?: return@withLock
        dao.putAlert(existing.copy(status = SiteAlertStatus.MUTED.name, mutedAt = now()))
    }

    override suspend fun prune() = lock.withLock {
        dao.deleteOlderThan(now() - SymptomPolicy.RETENTION_DAYS * 86_400_000L)
    }

    override suspend fun allow(site: String) = lock.withLock {
        val existing = dao.alert(site) ?: return@withLock
        dao.putAlert(existing.copy(status = SiteAlertStatus.ALLOWED.name))
    }
}

private fun SiteAlertEntity.toModel() = SiteAlert(
    site = site,
    browserPackage = browserPackage,
    channelId = channelId,
    status = SiteAlertStatus.entries.firstOrNull { it.name == status } ?: SiteAlertStatus.ASKED,
    date = date,
    count = count,
)

/** 사이트 광고 알림 판정 규칙 (FR-051). 기준값은 사례를 모은 뒤 정한다 (지금은 임시) */
object SiteAdPolicy {
    /** 광고 문구 없이 하루에 이만큼 보내면 광고 알림으로 본다 (임시) */
    const val DAILY_THRESHOLD = 5

    /** 광고·겁주기 문구가 있는 알림을 하루에 이만큼 보내면 광고 알림으로 본다 (임시) */
    const val AD_WORDS_THRESHOLD = 2

    /** 끄는 화면을 연 뒤 스위치를 끄기까지 기다리는 시간. 이 안에 온 알림은 "아직 켜져 있어요"로 세지 않는다 */
    const val MUTE_GRACE_MS = 60_000L

    /** 시니어를 겁주거나 누르게 하는 문구 (가짜 바이러스 경고·당첨·정리 유도) */
    private val AD_WORDS = listOf(
        "바이러스", "감염", "악성", "해킹", "당첨", "축하", "무료", "공짜", "정리", "청소", "최적화",
        "배터리", "속도", "느려", "경고", "위험", "지금 확인", "클릭", "포인트", "쿠폰", "이벤트",
    )

    /**
     * 브라우저가 사이트마다 만든 알림 채널 ID(`web:https://site.example;123`)에서 사이트 주소를 뽑는다. 사이트 채널이 아니면 null.
     * (Chrome 130·Android 15에서 확인한 형식, spec US10)
     */
    fun siteOf(channelId: String?): String? {
        val raw = channelId?.takeIf { it.startsWith("web:") }?.removePrefix("web:") ?: return null
        val site = raw.substringBefore(';').trim()
        return site.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.takeIf { it.substringAfter("://").isNotEmpty() }
    }

    fun hasAdWords(text: String): Boolean = AD_WORDS.any { text.contains(it, ignoreCase = true) }

    fun isAdSender(dailyCount: Int, adWordsCount: Int): Boolean =
        dailyCount >= DAILY_THRESHOLD || adWordsCount >= AD_WORDS_THRESHOLD
}
