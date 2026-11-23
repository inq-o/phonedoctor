package io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** S1 시니어 홈 */
@Serializable
data object SeniorHomeNavKey : NavKey

/** S4 검사 기록 */
@Serializable
data object ScanHistoryNavKey : NavKey

/** S7 설정 (이름, 가족 연결 관리, 글자 크기, 모든 데이터 지우기) */
@Serializable
data object SeniorSettingsNavKey : NavKey
