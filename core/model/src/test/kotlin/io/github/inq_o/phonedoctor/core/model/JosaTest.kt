package io.github.inq_o.phonedoctor.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class JosaTest {
    @Test
    fun picksByFinalConsonant() {
        assertEquals("초고속 클리너가", Josa.iGa("초고속 클리너"))
        assertEquals("손전등이", Josa.iGa("손전등"))
        assertEquals("손전등을", Josa.eulReul("손전등"))
        assertEquals("클리너는", Josa.eunNeun("클리너"))
    }

    @Test
    fun readsDigits() {
        assertEquals("App 2를", Josa.eulReul("App 2"))
        assertEquals("배터리 100이", Josa.iGa("배터리 100"))
        assertEquals("클리너 3은", Josa.eunNeun("클리너 3"))
        assertEquals("V9가", Josa.iGa("V9"))
    }

    @Test
    fun readsEnglishByEndingSound() {
        // 받침 없음
        assertEquals("bgad를", Josa.eulReul("bgad"))
        assertEquals("Super Cleaner가", Josa.iGa("Super Cleaner"))
        assertEquals("Flash Light는", Josa.eunNeun("Flash Light"))
        assertEquals("Phone Help를", Josa.eulReul("Phone Help"))
        assertEquals("Boost는", Josa.eunNeun("Boost"))
        assertEquals("Note는", Josa.eunNeun("Note"))
        assertEquals("Kakao가", Josa.iGa("Kakao"))
        assertEquals("Battery Free는", Josa.eunNeun("Battery Free"))
        // 받침 있음
        assertEquals("Zoom은", Josa.eunNeun("Zoom"))
        assertEquals("Instagram이", Josa.iGa("Instagram"))
        assertEquals("KakaoTalk을", Josa.eulReul("KakaoTalk"))
        assertEquals("Magic을", Josa.eulReul("Magic"))
        assertEquals("Clean App이", Josa.iGa("Clean App"))
        assertEquals("Chat은", Josa.eunNeun("Chat"))
        assertEquals("Google이", Josa.iGa("Google"))
        assertEquals("Smart Phone을", Josa.eulReul("Smart Phone"))
        assertEquals("Ringtone은", Josa.eunNeun("Ringtone"))
    }

    @Test
    fun readsAcronymsByLetterName() {
        assertEquals("SNS가", Josa.iGa("SNS"))
        assertEquals("KB는", Josa.eunNeun("KB"))
        assertEquals("XML을", Josa.eulReul("XML"))
        assertEquals("V를", Josa.eulReul("V"))
    }

    @Test
    fun skipsTrailingPunctuationAndKeepsBothWhenUnknown() {
        assertEquals("클리너 (무료)를", Josa.eulReul("클리너 (무료)"))
        assertEquals("★★이(가)", Josa.iGa("★★"))
        assertEquals("이(가)", Josa.iGa(""))
    }
}
