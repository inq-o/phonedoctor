package io.github.inq_o.phonedoctor.feature.onboarding.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 첫 설정: 공개 고지 → 알림 → 사용 기록 보기 */
@Serializable
data object OnboardingNavKey : NavKey

/** O1 역할 선택 "이 폰을 지켜요" / "가족 폰을 지켜요". 가족 연결을 쓸 수 있는 빌드의 첫 실행에서만 */
@Serializable
data object RoleChoiceNavKey : NavKey
