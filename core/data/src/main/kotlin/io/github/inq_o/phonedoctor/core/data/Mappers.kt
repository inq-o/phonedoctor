package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.database.AlertLocalEntity
import io.github.inq_o.phonedoctor.core.database.ScanRunEntity
import io.github.inq_o.phonedoctor.core.model.AlertFamilyState
import io.github.inq_o.phonedoctor.core.model.AlertKind
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.ScanResult
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert

internal fun AlertLocalEntity.toModel() = SymptomAlert(
    id = id,
    app = AppIdentity(packageName, signingCertSha256, versionCode),
    label = label,
    status = AlertStatus.valueOf(status),
    outcome = outcome?.let(AlertOutcome::valueOf),
    date = date, count = count, days = days, firstAt = firstAt, lastAt = lastAt,
    detectorVersion = detectorVersion, createdAt = createdAt, updatedAt = updatedAt,
    kind = if (kind == KIND_NOTICE) AlertKind.NOTICE else AlertKind.APPROVAL,
    family = serverAlertId?.let {
        AlertFamilyState(
            decision = decision?.let { d -> FamilyDecision.entries.firstOrNull { it.wire == d } },
            guardianName = guardianName,
            messageToSenior = messageToSenior,
            closed = serverStatus == SERVER_EXPIRED || (serverStatus == SERVER_RESOLVED && decision == null),
        )
    },
)

internal const val KIND_APPROVAL = "approval"
internal const val KIND_NOTICE = "notice"
internal const val SERVER_OPEN = "open"
internal const val SERVER_DECIDED = "decided"
internal const val SERVER_RESOLVED = "resolved"
internal const val SERVER_EXPIRED = "expired"

/** 서버·DB에 쓰는 이름 (openapi DecisionType) */
internal val FamilyDecision.wire: String
    get() = when (this) {
        FamilyDecision.RECOMMEND_UNINSTALL -> "recommend_uninstall"
        FamilyDecision.ALLOW -> "allow"
    }

internal fun ScanRunEntity.toModel() = ScanRun(
    id, startedAt, trigger, runCatching { ScanResult.valueOf(result) }.getOrDefault(ScanResult.ERROR), queryFrom, queryTo,
    eventCount, transitionCount, adCount, holdCount, storedCount, segmentId, segmentKind, quarantined, durationMs, note,
)

internal fun ScanRun.toEntity() = ScanRunEntity(
    id, startedAt, trigger, result.name, queryFrom, queryTo, eventCount, transitionCount, adCount, holdCount,
    storedCount, segmentId, segmentKind, quarantined, durationMs, note,
)
