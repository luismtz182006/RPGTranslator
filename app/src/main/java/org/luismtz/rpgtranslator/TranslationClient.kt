package org.luismtz.rpgtranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Cliente de traducción automática (endpoint público, no oficial, de Google
 * Translate — no requiere API key).
 *
 * - Cachea cada traducción exacta (mismo texto + mismo par de idiomas),
 *   precargable desde [TranslationCacheDb] y con checkpoints incrementales
 *   ([pendingCacheEntries] / [clearPendingCacheEntries]) para no perder
 *   trabajo si la app se cierra a la mitad.
 * - Un [RateLimiter] y un [Semaphore] compartidos evitan saturar el servicio,
 *   sin importar cuántos archivos se procesen en paralelo.
 * - [detectLanguage] permite detectar el idioma de origen UNA sola vez al
 *   principio de la corrida, en vez de mandar "auto" en cada solicitud.
 */
class TranslationClient(
    private val sourceLang: String,
    private val targetLang: String,
    initialCache: Map<String, String> = emptyMap(),
    private val config: TranslatorConfig = TranslatorConfig.DEFAULT,
    private val rateLimiter: RateLimiter = RateLimiter(config.requestsPerSecond),
    private val networkSemaphore: Semaphore = Semaphore(config.networkConcurrency)
) : TextTranslator {

    open class TranslationException(msg: String, cause: Throwable? = null) : Exception(msg, cause)
    class RateLimitException(msg: String) : TranslationException(msg)

    private val cache = ConcurrentHashMap<String, String>(initialCache)
    private val pendingSinceCheckpoint = ConcurrentHashMap<String, String>()

    @Volatile
    private var cancelled = false

    override fun cancel() { cancelled = true }

    override fun pendingCacheEntries(): Map<String, String> = pendingSinceCheckpoint.toMap()

    override fun clearPendingCacheEntries() { pendingSinceCheckpoint.clear() }

    /** Detecta el idioma de origen a partir de una muestra de texto (una sola llamada). */
    suspend fun detectLanguage(sample: String): String? {
        if (sample.isBlank()) return null
        return try {
            val (_, detected) = doRequestWithDetectedLang(sample, "auto")
            detected
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun translate(text: String): String {
        if (text.isBlank()) return text
        if (cancelled) throw TranslationException("cancelado por el usuario")

        cache[text]?.let { return it }

        var attempt = 0
        var lastError: Exception? = null
        while (attempt < config.maxRetries) {
            if (cancelled) throw TranslationException("cancelado por el usuario")
            try {
                val (result, _) = networkSemaphore.withPermit {
                    rateLimiter.acquire()
                    withContext(Dispatchers.IO) { doRequestWithDetectedLang(text, sourceLang) }
                }
                cache[text] = result
                pendingSinceCheckpoint[text] = result
                return result
            } catch (e: RateLimitException) {
                lastError = e
                attempt++
                delay(config.rateLimitBaseDelayMs * attempt + (0..300).random())
            } catch (e: Exception) {
                lastError = e
                attempt++
                delay(config.retryBaseDelayMs * attempt + (0..200).random())
            }
        }
        throw TranslationException("Fallo al traducir tras ${config.maxRetries} intentos: ${lastError?.message}", lastError)
    }

    /** Devuelve (texto_traducido, idioma_detectado_o_null). */
    private fun doRequestWithDetectedLang(text: String, srcLangOverride: String): Pair<String, String?> {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$srcLangOverride&tl=$targetLang&dt=t&q=$encoded"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = config.connectTimeoutMs
        conn.readTimeout = config.readTimeoutMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

        val code = conn.responseCode
        if (code == 429 || code == 503) {
            conn.disconnect()
            throw RateLimitException("HTTP $code (límite de volumen)")
        }
        if (code != 200) {
            conn.disconnect()
            throw TranslationException("HTTP $code")
        }

        val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        // Respuesta: [[["traducido","original",null,null,1], ...], null, "idioma_detectado"]
        val root = JSONArray(body)
        val segments = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            sb.append(seg.optString(0, ""))
        }
        val detected = root.optString(2, "").takeIf { it.isNotBlank() }
        return Pair(sb.toString(), detected)
    }

    companion object {
        /**
         * Protege los códigos de control de RPG Maker (\V[1], \C[2], \N[3], \I[4], \., \|, \!, etc.)
         * reemplazándolos por marcadores @@N@@ antes de traducir.
         */
        private val controlCodeRegex = Regex("""\\([A-Za-z]+(\[[^\]]*\])?|.)""")

        fun protectControlCodes(text: String): Pair<String, List<String>> {
            val codes = ArrayList<String>()
            val protectedText = controlCodeRegex.replace(text) { match ->
                codes.add(match.value)
                "@@${codes.size - 1}@@"
            }
            return Pair(protectedText, codes)
        }

        fun restoreControlCodes(text: String, codes: List<String>): String {
            var result = text
            for (i in codes.indices) {
                result = result.replace(Regex("""@@\s*$i\s*@@"""), Regex.escapeReplacement(codes[i]))
            }
            return result
        }

        /** Protege interpolaciones de Ren'Py: [variable], {tag}, {/tag} */
        private val renpyTagRegex = Regex("""\[[^\[\]]*\]|\{[^{}]*\}""")

        fun protectRenpyTags(text: String): Pair<String, List<String>> {
            val tags = ArrayList<String>()
            val protectedText = renpyTagRegex.replace(text) { match ->
                tags.add(match.value)
                "@@${tags.size - 1}@@"
            }
            return Pair(protectedText, tags)
        }

        fun restoreRenpyTags(text: String, tags: List<String>): String = restoreControlCodes(text, tags)
    }
}
