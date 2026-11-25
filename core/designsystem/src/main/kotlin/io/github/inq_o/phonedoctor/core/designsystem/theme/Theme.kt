/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.inq_o.phonedoctor.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    tertiary = Safe,
    onTertiary = OnPrimary,
    error = Risk,
    onError = OnPrimary,
    background = Background,
    onBackground = OnBackground,
    surface = SurfaceLight,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnBackground,
    outline = OutlineLight,
    // M3 기본(보라 계열)이 확인창·선택 칩·스위치 바탕에 새지 않게 같은 팔레트로 덮는다
    primaryContainer = BadgeInfoBg,
    onPrimaryContainer = BadgeInfoInk,
    secondary = OutlineLight,
    onSecondary = OnPrimary,
    secondaryContainer = BadgeInfoBg,
    onSecondaryContainer = BadgeInfoInk,
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = Background,
    surfaceContainer = Background,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = SurfaceVariantLight,
    surfaceTint = Color.Transparent,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    tertiary = SafeDark,
    onTertiary = OnPrimaryDark,
    error = RiskDark,
    onError = OnPrimaryDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnBackgroundDark,
    outline = OutlineDark,
    primaryContainer = BadgeInfoBgDark,
    onPrimaryContainer = BadgeInfoInkDark,
    secondary = OutlineDark,
    onSecondary = BackgroundDark,
    secondaryContainer = BadgeInfoBgDark,
    onSecondaryContainer = BadgeInfoInkDark,
    surfaceContainerLowest = BackgroundDark,
    surfaceContainerLow = BackgroundDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceDark,
    surfaceContainerHighest = SurfaceVariantDark,
    surfaceTint = Color.Transparent,
)

/** M3 역할에 없는 상태 색 */
@Immutable
data class StatusColors(val safe: Color, val caution: Color, val onStatus: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Safe, Caution, OnPrimary) }

/** 배지 한 종류의 글자·바탕 */
@Immutable
data class BadgeColor(val ink: Color, val background: Color)

/** 보호자 배지 (ux-flows.md §5): 확인 필요·답변 대기 / 실패 / 허용 / 삭제 요청·선택 / 끝난 결과 */
@Immutable
data class BadgeColors(val caution: BadgeColor, val risk: BadgeColor, val safe: BadgeColor, val info: BadgeColor, val neutral: BadgeColor)

private val LightBadges = BadgeColors(
    BadgeColor(BadgeCautionInk, BadgeCautionBg), BadgeColor(BadgeRiskInk, BadgeRiskBg), BadgeColor(BadgeSafeInk, BadgeSafeBg),
    BadgeColor(BadgeInfoInk, BadgeInfoBg), BadgeColor(BadgeNeutralInk, BadgeNeutralBg),
)
private val DarkBadges = BadgeColors(
    BadgeColor(BadgeCautionInkDark, BadgeCautionBgDark), BadgeColor(BadgeRiskInkDark, BadgeRiskBgDark), BadgeColor(BadgeSafeInkDark, BadgeSafeBgDark),
    BadgeColor(BadgeInfoInkDark, BadgeInfoBgDark), BadgeColor(BadgeNeutralInkDark, BadgeNeutralBgDark),
)

val LocalBadgeColors = staticCompositionLocalOf { LightBadges }

/**
 * 시니어 화면 테마. 색·위치를 전화로 설명할 수 있게 Dynamic Color는 쓰지 않는다 (ux-flows.md 원칙 2).
 * 어두운 화면은 시스템 설정을 따른다.
 */
@Composable
fun PhoneDoctorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val status = if (darkTheme) StatusColors(SafeDark, CautionDark, OnPrimaryDark) else StatusColors(Safe, Caution, OnPrimary)
    CompositionLocalProvider(LocalStatusColors provides status, LocalBadgeColors provides if (darkTheme) DarkBadges else LightBadges) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/** 보호자 화면 글자 크기. 색은 감싼 테마(PhoneDoctorTheme)를 그대로 쓴다 */
@Composable
fun GuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = GuardianTypography, content = content)
}
