package io.github.inq_o.phonedoctor.feature.seniorappdetail.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** S2 앱 상세 · 삭제 유도. [alertId]는 기기 안 확인 요청 ID */
@Serializable
data class AppDetailNavKey(val alertId: Long) : NavKey
