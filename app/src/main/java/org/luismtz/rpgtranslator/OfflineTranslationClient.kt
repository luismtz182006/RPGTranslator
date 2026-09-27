package org.luismtz.rpgtranslator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Traduce completamente en el dispositivo, sin internet, usando ML Kit.
 *
 * El modelo del idioma se descarga UNA sola vez (llamar [ensureModelDownloaded]
 * antes de traducir, requiere internet solo esa primera vez); después, cada
 * llamada a [translate] es local.
 *
 * ML Kit no soporta detección automática de idioma de origen ("auto").
 *
 * **Cancelación — best effort:** [cancel] evita que se INICIEN nuevas
 * traducciones, pero una llamada ya en curso a `Tasks.await(...)` es
 * bloqueante y corre hasta terminar (hasta 30s de timeout) — no hay forma de
 * interrumpirla a la mitad con la API de ML Kit tal como está expuesta aquí.
 * En la práctica esto significa que cancelar detiene el trabajo en, como
 * mucho, unos segundos — no de forma instantánea.
 */
class OfflineTranslationClient(
    sourceLangTag: String,
    targetLangTag: String
) : TextTranslator {

    class UnsupportedLanguageException(msg: String) : Exception(msg)
    class OfflineTranslationException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private val cache = ConcurrentHashMap<String, String>()
    private val pendingSinceCheckpoint = ConcurrentHashMap<String, String>()

    @Volatile
    private var cancelled = false

    private val translator: Translator

    init {
        if (sourceLangTag.equals("auto", ignoreCase = true)) {
            throw UnsupportedLanguageException(
                "El modo local no puede detectar el idioma automáticamente: " +
                    "escribe el idioma de origen real (ej. 'ja' para japonés) en vez de 'auto'."
            )
        }
        val sourceCode = TranslateLanguage.fromLanguageTag(sourceLangTag)
            ?: throw UnsupportedLanguageException("Idioma de origen '$sourceLangTag' no soportado por el traductor local")
        val targetCode = TranslateLanguage.fromLanguageTag(targetLangTag)
            ?: throw UnsupportedLanguageException("Idioma de destino '$targetLangTag' no soportado por el traductor local")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        translator = Translation.getClient(options)
    }

    /** Descarga el modelo si hace falta. Bloqueante — llamar desde un hilo/corrutina de fondo. */
    suspend fun ensureModelDownloaded(requireWifi: Boolean = false) = withContext(Dispatchers.IO) {
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        try {
            Tasks.await(translator.downloadModelIfNeeded(conditions), 10, TimeUnit.MINUTES)
        } catch (e: Exception) {
            throw OfflineTranslationException("No se pudo descargar el modelo de idioma: ${e.message}", e)
        }
    }

    override fun cancel() { cancelled = true }
    override fun pendingCacheEntries(): Map<String, String> = pendingSinceCheckpoint.toMap()
    override fun clearPendingCacheEntries() { pendingSinceCheckpoint.clear() }

    override suspend fun translate(text: String): String {
        if (text.isBlank()) return text
        if (cancelled) throw OfflineTranslationException("cancelado por el usuario")
        cache[text]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val result = Tasks.await(translator.translate(text), 30, TimeUnit.SECONDS)
                cache[text] = result
                pendingSinceCheckpoint[text] = result
                result
            } catch (e: Exception) {
                throw OfflineTranslationException("fallo al traducir localmente: ${e.message}", e)
            }
        }
    }

    fun close() = translator.close()
}
