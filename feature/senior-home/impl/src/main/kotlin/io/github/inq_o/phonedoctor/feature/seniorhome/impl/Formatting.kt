package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TimeFormat = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

internal fun zoned(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

internal fun timeOfDay(time: ZonedDateTime): String = TimeFormat.format(time)

/** 근거 날짜가 오늘이면 "오늘", 아니면 날짜를 적는다 (ux-flows.md "오늘" 표기 규칙) */
@Composable
internal fun symptomSentence(alert: SymptomAlert): String {
    val date = runCatching { LocalDate.parse(alert.date) }.getOrNull()
    return if (date == null || date == LocalDate.now()) {
        stringResource(R.string.feature_senior_home_impl_alert_today, alert.count)
    } else {
        stringResource(R.string.feature_senior_home_impl_alert_date, date.monthValue, date.dayOfMonth, alert.count)
    }
}
