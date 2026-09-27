package org.luismtz.rpgtranslator

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationClientCodeProtectionTest {

    @Test
    fun `protects and restores a single RPG Maker control code`() {
        val original = "Hello \\N[1], take this!"
        val (protectedText, codes) = TranslationClient.protectControlCodes(original)
        assert(!protectedText.contains("\\N"))
        val restored = TranslationClient.restoreControlCodes(protectedText, codes)
        assertEquals(original, restored)
    }

    @Test
    fun `protects and restores multiple mixed control codes`() {
        val original = "\\C[2]Danger!\\C[0] HP: \\V[10]\\!"
        val (protectedText, codes) = TranslationClient.protectControlCodes(original)
        val restored = TranslationClient.restoreControlCodes(protectedText, codes)
        assertEquals(original, restored)
    }

    @Test
    fun `text with no control codes is unchanged`() {
        val original = "Just plain text."
        val (protectedText, codes) = TranslationClient.protectControlCodes(original)
        assertEquals(original, protectedText)
        assertEquals(0, codes.size)
    }

    @Test
    fun `protects and restores Renpy variable interpolation and tags`() {
        val original = "Hello [player_name], welcome to {b}town{/b}!"
        val (protectedText, tags) = TranslationClient.protectRenpyTags(original)
        assert(!protectedText.contains("[player_name]"))
        assert(!protectedText.contains("{b}"))
        val restored = TranslationClient.restoreRenpyTags(protectedText, tags)
        assertEquals(original, restored)
    }
}
