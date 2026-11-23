package io.github.inq_o.phonedoctor.feature.guardian.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** O-G1 Google 로그인 */
@Serializable
data object GuardianSignInNavKey : NavKey

/** G1 보호자 홈: 연결한 부모님 폰 목록 */
@Serializable
data object GuardianHomeNavKey : NavKey

/** O-G2 연결 코드 6자리 입력·QR 읽기 */
@Serializable
data object EnterCodeNavKey : NavKey

/** O-G3 "부모님 폰에서 [연결할게요]를 눌러 주세요" 대기. [seniorName]은 코드를 넣었을 때 서버가 알려 준 이름 */
@Serializable
data class LinkWaitingNavKey(val linkId: String, val seniorName: String) : NavKey

/** G2 요청 상세·응답 */
@Serializable
data class AlertDetailNavKey(val alertId: String) : NavKey

/** G3 지난 요청 (알림 전용 요청은 '경고 앱'으로 표시) */
@Serializable
data object AlertHistoryNavKey : NavKey
