package io.github.inq_o.phonedoctor.feature.seniorsites.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 사이트 광고 알림: 알림 접근을 허용했으면 사이트 목록, 아니면 끄는 법 안내 (S6, FR-040) */
@Serializable
data object SitesNavKey : NavKey

/** 광고 알림을 보내는 사이트 하나. [openSettings]면 화면이 열리자마자 그 사이트 알림 설정을 연다 (알림 한 번 탭으로, FR-052) */
@Serializable
data class SiteAlertNavKey(val site: String, val openSettings: Boolean = false) : NavKey
