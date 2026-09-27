package org.luismtz.rpgtranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenpyLineParserTest {

    @Test
    fun `matches simple dialogue line with speaker`() {
        val m = RenpyLineParser.matchDialogue("    e \"Hello there!\"")
        assertEquals("e ", m?.speaker)
        assertEquals("Hello there!", m?.text)
    }

    @Test
    fun `matches narrator line without speaker`() {
        val m = RenpyLineParser.matchDialogue("\"Just narration.\"")
        assertEquals("", m?.speaker ?: "")
        assertEquals("Just narration.", m?.text)
    }

    @Test
    fun `does not match code lines`() {
        assertNull(RenpyLineParser.matchDialogue("    $ variable = \"not dialogue\""))
        assertNull(RenpyLineParser.matchDialogue("    define e = Character(\"Eileen\")"))
        assertNull(RenpyLineParser.matchDialogue("label start:"))
    }

    @Test
    fun `matches menu choice line`() {
        val m = RenpyLineParser.matchChoice("        \"Go north.\":")
        assertEquals("Go north.", m?.text)
    }

    @Test
    fun `preserves escaped quotes inside dialogue`() {
        val m = RenpyLineParser.matchDialogue("e \"She said \\\"hi\\\" to me.\"")
        assertEquals("She said \\\"hi\\\" to me.", m?.text)
    }
}
