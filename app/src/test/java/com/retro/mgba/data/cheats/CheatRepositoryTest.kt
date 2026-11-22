package com.retro.mgba.data.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatRepositoryTest {

    @Test
    fun normalizeCodeLines_handlesWhitespaceNewlinesAndPlusSeparators() {
        val raw = "  82025840 0044 \r\n\n  +  D8BAE4D9 4864DCE5+A86CDBA5 19BA49B3  \r\n   "
        val lines = CheatRepository.normalizeCodeLines(raw)
        assertEquals(
            listOf(
                "82025840 0044",
                "D8BAE4D9 4864DCE5",
                "A86CDBA5 19BA49B3"
            ),
            lines
        )
    }

    @Test
    fun normalizeCodeLines_emptyAndBlankInputReturnsEmptyList() {
        assertTrue(CheatRepository.normalizeCodeLines("").isEmpty())
        assertTrue(CheatRepository.normalizeCodeLines("   \r\n  +  \t ").isEmpty())
    }

    @Test
    fun parseChtContent_mgbaFormatPreservesEnabledAndDisabledDirectives() {
        val content = """
            # Master Code
            D8BAE4D9 4864DCE5
            A86CDBA5 19BA49B3

            !disabled
            # Rare Candy
            82025840 0044

            # Empty Header Should Be Ignored
        """.trimIndent()

        val parsed = CheatRepository.parseChtContent(content)
        assertEquals(2, parsed.size)

        assertEquals("Master Code", parsed[0].name)
        assertTrue(parsed[0].enabled)
        assertEquals(listOf("D8BAE4D9 4864DCE5", "A86CDBA5 19BA49B3"), parsed[0].lines)

        assertEquals("Rare Candy", parsed[1].name)
        assertFalse(parsed[1].enabled)
        assertEquals(listOf("82025840 0044"), parsed[1].lines)
    }

    @Test
    fun parseChtContent_libretroFormatParsesIndicesAndMultiLinePlusCodes() {
        val content = """
            cheats = 2
            cheat0_desc = "Walk Through Walls"
            cheat0_code = "509197D3 542975F4+78DA95DF 44018CB4"
            cheat0_enable = true
            cheat1_desc = "Max Money"
            cheat1_code = "820257BC 423F"
            cheat1_enable = false
        """.trimIndent()

        val parsed = CheatRepository.parseChtContent(content)
        assertEquals(2, parsed.size)

        assertEquals("Walk Through Walls", parsed[0].name)
        assertTrue(parsed[0].enabled)
        assertEquals(listOf("509197D3 542975F4", "78DA95DF 44018CB4"), parsed[0].lines)

        assertEquals("Max Money", parsed[1].name)
        assertFalse(parsed[1].enabled)
        assertEquals(listOf("820257BC 423F"), parsed[1].lines)
    }

    @Test
    fun sanitizeGameTitle_handlesEmptyAndSpecialCharacters() {
        assertEquals("game", CheatRepository.sanitizeGameTitle(null))
        assertEquals("game", CheatRepository.sanitizeGameTitle("   "))
        assertEquals("Pokemon_ Emerald _U_", CheatRepository.sanitizeGameTitle("Pokemon: Emerald (U)"))
    }
}
