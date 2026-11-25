package io.github.inq_o.phonedoctor.core.model

/**
 * 보호자 화면에서 상대 폰을 부르는 이름 (ux-flows §2 "보호자 화면의 상대 폰 이름").
 * 부모님·엄마 같은 역할을 문구에 박지 않고, 아래 순서로 고른다.
 * 1. 보호자가 고른 표시 이름(관계) → "엄마 폰"
 * 2. 상대가 직접 정한 이름 → "김영희님 폰"
 * 3. 둘 다 없음 → "연결한 폰"
 * 화면 문구는 각 모듈의 문자열 자원으로 만든다 (이 값은 어느 경우인지와 이름만 담는다)
 */
sealed interface PhoneName {
    /** 보호자가 고른 표시 이름 */
    data class Chosen(val name: String) : PhoneName

    /** 상대가 직접 정한 이름 */
    data class Own(val name: String) : PhoneName

    data object Unnamed : PhoneName

    companion object {
        fun of(chosen: String?, own: String?): PhoneName {
            chosen?.trim()?.takeIf { it.isNotEmpty() }?.let { return Chosen(it) }
            own?.trim()?.takeIf { it.isNotEmpty() }?.let { return Own(it) }
            return Unnamed
        }
    }
}

/** 보호자가 고른 표시 이름 직접 입력 최대 길이 (ux-flows §4 O-G4) */
const val PHONE_NAME_MAX = 10

/** 보호자 폰에 저장한 표시 이름을 연결 ID로 찾는다. 알림처럼 화면 밖에서 이름이 필요할 때 쓴다 (네트워크 없음) */
fun interface PhoneNameLookup {
    fun chosenName(linkId: String): String?
}
