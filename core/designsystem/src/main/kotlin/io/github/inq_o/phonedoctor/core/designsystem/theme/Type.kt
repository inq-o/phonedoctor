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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

// 한국어는 낱자 단위로 줄이 바뀌면 읽기 어렵다. 어절 단위로 끊는다 (Android 13 이상에서 적용)
private val KoreanLineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

// 시니어 화면 기준 (ux-flows.md §5): 상태 문장 28sp Bold, 본문 20sp 줄간격 1.5, 버튼 22sp Bold.
// 시스템 글꼴을 그대로 쓰고 sp 단위라 시스템 글꼴 크기 설정을 따른다
val Typography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 38.sp,
        lineBreak = KoreanLineBreak,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        lineBreak = KoreanLineBreak,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 30.sp,
        lineBreak = KoreanLineBreak,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        lineBreak = KoreanLineBreak,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        lineBreak = KoreanLineBreak,
    ),
)

// 보호자 화면 기준 (ux-flows.md §5): 4단계 — 상태 22 Bold / 제목·앱 이름 18 Bold / 본문·버튼 16 / 보조 14
private val Default = Typography()

val GuardianTypography = Typography(
    // 홈 위 막대 "폰주치의"와 상태 문장 ("확인 필요 1건")
    headlineSmall = Default.headlineSmall.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, lineBreak = KoreanLineBreak),
    titleLarge = Default.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, lineBreak = KoreanLineBreak),
    // 화면 제목(뒤로 옆)·앱 이름·확인창 제목
    titleMedium = Default.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, lineBreak = KoreanLineBreak),
    // 목록 줄 제목 (앱 이름 16 Bold)
    titleSmall = Default.titleSmall.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, lineBreak = KoreanLineBreak),
    bodyLarge = Default.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, lineBreak = KoreanLineBreak),
    // 보조: 묶음 제목·시각·설명
    bodyMedium = Default.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, lineBreak = KoreanLineBreak),
    // 입력칸 도움말·떠 있는 라벨 등 M3 부품 기본값도 14 밑으로 내려가지 않게
    bodySmall = Default.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp, lineBreak = KoreanLineBreak),
    labelSmall = Default.labelSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = Default.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)
