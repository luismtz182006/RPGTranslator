package org.luismtz.rpgtranslator

/** Contrato común para cualquier motor de traducción (en línea o local). */
interface TextTranslator {
    fun translate(text: String): String
    fun cancel()
    fun exportCache(): Map<String, String>
}
