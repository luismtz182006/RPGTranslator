package org.luismtz.rpgtranslator

/** Contrato común para cualquier motor de traducción (en línea o local), basado en corrutinas. */
interface TextTranslator {
    suspend fun translate(text: String): String
    fun cancel()
    /** Traducciones nuevas desde el último checkpoint que aún no se han guardado a disco. */
    fun pendingCacheEntries(): Map<String, String>
    /** Marca como ya guardadas las entradas devueltas por [pendingCacheEntries]. */
    fun clearPendingCacheEntries()
}
