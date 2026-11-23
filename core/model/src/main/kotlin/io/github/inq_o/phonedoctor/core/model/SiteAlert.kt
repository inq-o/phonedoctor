package io.github.inq_o.phonedoctor.core.model

/** 광고 알림을 보내는 사이트에 대한 시니어의 선택 (US10) */
enum class SiteAlertStatus {
    /** "끌까요?" 물어봄 */
    ASKED,

    /** 그 사이트 알림 설정 화면을 열었음 (스위치를 껐는지는 다음 알림이 오는지로 안다) */
    MUTED,

    /** 시니어가 괜찮다고 함 (뉴스·은행 등). 다시 묻지 않는다 */
    ALLOWED,

    /** 끄는 화면을 연 뒤에도 알림이 옴 */
    STILL_ON,
}

/** 광고 알림을 보내는 Chrome 사이트 하나 */
data class SiteAlert(
    /** 사이트 주소 (https://…) */
    val site: String,
    val browserPackage: String,
    val channelId: String,
    val status: SiteAlertStatus,
    /** 물었을 때의 근거: 그날 받은 알림 수 */
    val date: String,
    val count: Int,
) {
    /** 화면에 보일 이름: 주소에서 https:// 와 www. 를 뺀 것 */
    val displayName: String
        get() = site.substringAfter("://").removePrefix("www.").substringBefore('/')
}
