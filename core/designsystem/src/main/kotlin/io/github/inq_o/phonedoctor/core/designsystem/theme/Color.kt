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
val SurfaceVariantDark = Color(0xFF334155)
val OutlineDark = Color(0xFFCBD5E1)
