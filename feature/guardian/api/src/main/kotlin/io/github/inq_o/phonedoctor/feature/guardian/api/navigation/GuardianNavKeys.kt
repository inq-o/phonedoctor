package io.github.inq_o.phonedoctor.feature.guardian.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** O-G1 Google 로그인 */
@Serializable
data object GuardianSignInNavKey : NavKey

/** G1 보호자 홈: 지켜보는 폰 목록과 답변할 요청 */
@Serializable
data object GuardianHomeNavKey : NavKey

/** O-G2 연결 코드 6자리 입력·QR 읽기 */
@Serializable
data object EnterCodeNavKey : NavKey

/** O-G3 연결 요청 대기, 연결되면 O-G4 표시 이름 고르기. [seniorName]은 코드를 넣었을 때 서버가 알려 준 상대가 정한 이름(없으면 빈 값) */
@Serializable
data class LinkWaitingNavKey(val linkId: String, val seniorName: String) : NavKey

/** G2 요청 상세·응답 */
@Serializable
data class AlertDetailNavKey(val alertId: String) : NavKey

/** G3 지난 요청 (알림 전용 요청은 '허용한 앱 광고'로 표시) */
@Serializable
data object AlertHistoryNavKey : NavKey

/** G4 보호자 설정 (주의 단계도 받기, 이름, 모든 데이터 지우기) */
@Serializable
data object GuardianSettingsNavKey : NavKey
