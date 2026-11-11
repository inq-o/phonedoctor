package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.database.AlertLocalEntity
import io.github.inq_o.phonedoctor.core.database.ScanRunEntity
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
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
)

internal fun ScanRunEntity.toModel() = ScanRun(
    id, startedAt, trigger, runCatching { ScanResult.valueOf(result) }.getOrDefault(ScanResult.ERROR), queryFrom, queryTo,
    eventCount, transitionCount, adCount, holdCount, storedCount, segmentId, segmentKind, quarantined, durationMs, note,
)

internal fun ScanRun.toEntity() = ScanRunEntity(
    id, startedAt, trigger, result.name, queryFrom, queryTo, eventCount, transitionCount, adCount, holdCount,
    storedCount, segmentId, segmentKind, quarantined, durationMs, note,
)
