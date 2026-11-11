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
    fun keepsBothWhenNotHangul() {
        assertEquals("bgad이(가)", Josa.iGa("bgad"))
        assertEquals("App 2을(를)", Josa.eulReul("App 2"))
    }
}
