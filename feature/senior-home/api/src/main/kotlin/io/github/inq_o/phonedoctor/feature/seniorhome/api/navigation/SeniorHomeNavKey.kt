package io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** S1 시니어 홈 */
@Serializable
data object SeniorHomeNavKey : NavKey

/** S4 검사 기록 */
@Serializable
data object ScanHistoryNavKey : NavKey
