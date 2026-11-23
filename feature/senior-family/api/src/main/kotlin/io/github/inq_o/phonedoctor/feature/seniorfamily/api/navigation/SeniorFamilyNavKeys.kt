package io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** S5 가족 연결 관리 (코드 다시 받기, 연결 끊기) */
@Serializable
data object FamilyLinksNavKey : NavKey

/** O-S4 연결 코드·QR. 화면이 열리면 바로 새 코드를 받으므로 시니어가 연결하겠다고 누른 뒤에만 연다 */
@Serializable
data object PairingCodeNavKey : NavKey

/** S3 연결 확인 "○○님과 연결할까요?" */
@Serializable
data class LinkConfirmNavKey(val linkId: String) : NavKey
