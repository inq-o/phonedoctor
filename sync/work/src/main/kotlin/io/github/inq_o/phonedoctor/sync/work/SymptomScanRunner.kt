package io.github.inq_o.phonedoctor.sync.work

import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.FamilySyncScheduler
import io.github.inq_o.phonedoctor.core.data.ScanReport
import io.github.inq_o.phonedoctor.core.data.SiteAlertRepository
import io.github.inq_o.phonedoctor.core.data.SymptomScanner
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.data.isGuardianPhone
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 증상 감지 검사 1회와 그 뒤처리(새로 연 요청 알림, 닫힌 요청 알림 지우기, 사이트 알림 기록 정리, 가족 동기화 예약).
 * 주기 작업과 "휴대폰 검사하기" AppFunction이 같이 쓴다 (한쪽만 뒤처리를 하면 알림이 빠진다)
 */
@Singleton
class SymptomScanRunner @Inject constructor(
    private val scanner: SymptomScanner,
    private val notifier: Notifier,
    private val settings: UserSettingsRepository,
    private val family: FamilyRepository,
    private val familySync: FamilySyncScheduler,
    private val siteAlerts: SiteAlertRepository,
) {
    /** 보호자 폰이면 검사하지 않고 null */
    suspend fun run(trigger: String): ScanReport? {
        // 보호자 폰은 자기 앱을 살펴보지 않는다 (주기 작업은 앱이 뜰 때 역할과 관계없이 걸린다)
        if (isGuardianPhone(settings.chosenRole.first(), family.role.first(), family.available)) return null
        return finishScan(
            report = scanner.scan(trigger),
            notifyOpened = notifier::notifyAlert,
            cancelClosed = notifier::cancelAlert,
            prune = siteAlerts::prune,
            senior = family.role.first() == DeviceRole.SENIOR,
            familySync = familySync,
        )
    }
}

/** 검사 뒤처리 (시험하려고 떼어 냄) */
internal suspend fun finishScan(
    report: ScanReport,
    notifyOpened: (SymptomAlert) -> Unit,
    cancelClosed: (Long) -> Unit,
    prune: suspend () -> Unit,
    senior: Boolean,
    familySync: FamilySyncScheduler,
): ScanReport {
    report.closed.forEach(cancelClosed)
    report.opened.forEach(notifyOpened)
    // 사이트 알림 기록도 30일 지나면 지운다 (사이트 알림이 끊겨도 남지 않게)
    prune()
    // 가족과 연결한 시니어 폰: 새 근거·결과를 보내고, 6시간마다 보호 상태를 알린다
    if (senior) familySync.schedule()
    return report
}
