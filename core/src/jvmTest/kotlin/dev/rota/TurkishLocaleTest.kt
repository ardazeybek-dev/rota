package dev.rota

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** "FRI".lowercase() under tr-TR must still be "fri", not "frı". */
class TurkishLocaleTest {
    @Test
    fun parsesKeywordsUnderTurkishLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val rota = dev.rota.parse.RotaParser.parse("PERIOD 2026-11\nSHIFT Night FRI 22:00-06:00 REQUIRES PAGER\nPERSON Işık SKILLS PAGER AVOID FRI").rota
            assertEquals(setOf("pager"), rota.shifts.single().requires)
            assertEquals(setOf("pager"), rota.people.single().skills)
            kotlin.test.assertTrue(RotaEngine.plan(Examples.ONCALL.source.uppercase(Locale.ROOT)).complete)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
