package io.github.inq_o.phonedoctor.feature.onboarding.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 첫 설정: 공개 고지 → 알림 → 사용 기록 보기 */
@Serializable
data object OnboardingNavKey : NavKey
