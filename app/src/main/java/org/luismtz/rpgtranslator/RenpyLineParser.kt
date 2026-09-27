package org.luismtz.rpgtranslator

/**
 * Reconocimiento de líneas de diálogo/opciones de Ren'Py por expresión
 * regular (ver limitación conocida documentada en [RenpyTranslator]).
 * Vive en un objeto aparte, sin dependencias de Android, para poder probarlo
 * con JUnit normal (sin Robolectric ni instrumentación).
 */
object RenpyLineParser {
    val dialogueRegex = Regex("""^(\s*)([A-Za-z_][A-Za-z0-9_.]*\s+)?"((?:[^"\\]|\\.)*)"(\s*)$""")
    val choiceRegex = Regex("""^(\s*)"((?:[^"\\]|\\.)*)"(\s*):(\s*)$""")

    data class DialogueMatch(val indent: String, val speaker: String, val text: String, val trail: String)
    data class ChoiceMatch(val indent: String, val text: String, val mid: String, val trail: String)

    fun matchDialogue(line: String): DialogueMatch? {
        val m = dialogueRegex.matchEntire(line) ?: return null
        val (indent, speaker, text, trail) = m.destructured
        return DialogueMatch(indent, speaker, text, trail)
    }

    fun matchChoice(line: String): ChoiceMatch? {
        val m = choiceRegex.matchEntire(line) ?: return null
        val (indent, text, mid, trail) = m.destructured
        return ChoiceMatch(indent, text, mid, trail)
    }
}
