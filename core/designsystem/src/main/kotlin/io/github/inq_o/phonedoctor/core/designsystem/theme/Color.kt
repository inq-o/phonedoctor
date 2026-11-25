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

import androidx.compose.ui.graphics.Color

// ux-flows.md §5 디자인 토큰. 대비는 WCAG 상대 휘도 공식으로 계산한 값
val Primary = Color(0xFF0369A1)
val OnPrimary = Color(0xFFFFFFFF)
val Safe = Color(0xFF15803D)
val Risk = Color(0xFFB91C1C)
val Caution = Color(0xFFB45309)
val Background = Color(0xFFF8FAFC)
val OnBackground = Color(0xFF0F172A)
// 카드·테두리 버튼·메뉴 칸 바탕 (ux-flows §5 surface)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFE2E8F0)
val OutlineLight = Color(0xFF475569)

// 어두운 화면: 같은 대비 기준 (본문 7:1 이상)
val PrimaryDark = Color(0xFF7DD3FC)
val OnPrimaryDark = Color(0xFF082F49)
val SafeDark = Color(0xFF86EFAC)
val RiskDark = Color(0xFFFCA5A5)
val CautionDark = Color(0xFFFCD34D)
val BackgroundDark = Color(0xFF0F172A)
val OnBackgroundDark = Color(0xFFF8FAFC)
val SurfaceDark = Color(0xFF1E293B)
val SurfaceVariantDark = Color(0xFF334155)
val OutlineDark = Color(0xFFCBD5E1)

// 보호자 배지 (ux-flows.md §5 배지 토큰): 글자색을 상태색보다 진하게 해 14sp 배지도 7:1을 넘긴다
val BadgeCautionInk = Color(0xFF78350F)
val BadgeCautionBg = Color(0xFFFEF3C7)
val BadgeRiskInk = Color(0xFF7F1D1D)
val BadgeRiskBg = Color(0xFFFEE2E2)
val BadgeSafeInk = Color(0xFF14532D)
val BadgeSafeBg = Color(0xFFDCFCE7)
val BadgeInfoInk = Color(0xFF0C4A6E)
val BadgeInfoBg = Color(0xFFE0F2FE)
val BadgeNeutralInk = Color(0xFF0F172A)
val BadgeNeutralBg = Color(0xFFE2E8F0)
val BadgeCautionInkDark = Color(0xFFFDE68A)
val BadgeCautionBgDark = Color(0xFF451A03)
val BadgeRiskInkDark = Color(0xFFFECACA)
val BadgeRiskBgDark = Color(0xFF450A0A)
val BadgeSafeInkDark = Color(0xFFBBF7D0)
val BadgeSafeBgDark = Color(0xFF052E16)
val BadgeInfoInkDark = Color(0xFFBAE6FD)
val BadgeInfoBgDark = Color(0xFF082F49)
val BadgeNeutralInkDark = Color(0xFFF8FAFC)
val BadgeNeutralBgDark = Color(0xFF334155)
