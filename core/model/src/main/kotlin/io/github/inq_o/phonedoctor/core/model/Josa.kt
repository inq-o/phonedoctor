package io.github.inq_o.phonedoctor.core.model

/**
 * 받침에 맞는 조사를 붙인다 (ux-flows.md §6). 앱 이름은 영어·숫자로 끝나는 경우가 많아 읽는 소리로 판단한다.
 * - 한글: 마지막 글자의 받침
 * - 숫자: 읽는 소리 (0 영·1 일·3 삼·6 육·7 칠·8 팔은 받침, 2 이·4 사·5 오·9 구는 없음. 10·100은 십·백이라 0과 같다)
 * - 모두 대문자인 약자(SNS, KB): 마지막 글자 이름. L 엘·M 엠·N 엔·R 알만 받침
 * - 영어 단어: 끝소리. l·m·n·ng(클린, 줌, 롱)·k·c(톡, 매직)·짧은 모음 뒤 p·t(앱, 챗)·읽지 않는 e 앞 l·m·n(구글, 폰)은 받침,
 *   나머지(모음, r, 드·스·프처럼 "으"로 끝나는 소리)는 없음. 어긋나는 단어도 있지만 괄호로 둘 다 적는 것보다 맞는 경우가 훨씬 많다
 * - 글자·숫자가 없으면 "이(가)"처럼 둘 다 적는다
 */
object Josa {
    fun iGa(word: String) = word + pick(word, "이", "가")
    fun eulReul(word: String) = word + pick(word, "을", "를")
    fun eunNeun(word: String) = word + pick(word, "은", "는")

    private fun pick(word: String, withFinal: String, withoutFinal: String): String =
        when (hasFinal(word)) {
            true -> withFinal
            false -> withoutFinal
            null -> "$withFinal($withoutFinal)"
        }

    /** 마지막 소리에 받침이 있으면 true, 없으면 false, 판단할 수 없으면 null */
    internal fun hasFinal(word: String): Boolean? {
        // 끝의 괄호·문장부호·공백은 건너뛴다 ("클리너 (무료)"는 "무료"로 판단)
        val tail = word.trimEnd { !it.isLetterOrDigit() }
        val last = tail.lastOrNull() ?: return null
        return when {
            last in '가'..'힣' -> (last - '가') % 28 != 0
            last in '0'..'9' -> last in "013678"
            last.isAsciiLetter() -> english(tail)
            else -> null
        }
    }

    private fun english(tail: String): Boolean {
        val letters = tail.takeLastWhile { it.isAsciiLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return letters.last() in "LMNR"
        if (letters.length == 1) return letters.uppercase() in "LMNR"
        val w = letters.lowercase()
        val last = w.last()
        val prev = w[w.length - 2]
        return when {
            // 읽지 않는 끝 e: 앞 자음으로 판단. Google 구글·Phone 폰·Time 타임은 받침, Note 노트·Like 라이크는 없음. ee·ie(Free)는 모음
            last == 'e' && w.length >= 3 && !prev.isVowel() -> prev in "lmn"
            last in "lmn" -> true
            w.endsWith("ng") -> true
            last == 'k' || last == 'c' -> true
            // 짧은 모음 하나 뒤 p·t: 앱(app)·맵(map)·챗(chat)·컷(cut). 모음 둘(boat 보트)·자음 뒤(help 헬프, light 라이트)는 없음
            last == 'p' || last == 't' -> {
                val stem = if (prev == last) w.dropLast(1) else w
                val v = stem.length - 2
                v >= 0 && stem[v].isVowel() && (v == 0 || !stem[v - 1].isVowel())
            }
            else -> false
        }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isVowel() = lowercaseChar() in "aeiou"
}
