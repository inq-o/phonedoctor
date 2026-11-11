package io.github.inq_o.phonedoctor.core.model

/** 받침에 맞는 조사를 붙인다. 한글로 끝나지 않으면 "이(가)"처럼 둘 다 적는다 (ux-flows.md §6) */
object Josa {
    fun iGa(word: String) = word + pick(word, "이", "가")
    fun eulReul(word: String) = word + pick(word, "을", "를")
    fun eunNeun(word: String) = word + pick(word, "은", "는")

    private fun pick(word: String, withFinal: String, withoutFinal: String): String {
        val last = word.trimEnd().lastOrNull() ?: return "$withFinal($withoutFinal)"
        if (last !in '가'..'힣') return "$withFinal($withoutFinal)"
        return if ((last - '가') % 28 != 0) withFinal else withoutFinal
    }
}
