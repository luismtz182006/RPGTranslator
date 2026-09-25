package org.luismtz.rpgtranslator

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Cliente de traducción automática. Usa el endpoint público (no oficial) de
 * Google Translate — el mismo que usan la mayoría de las herramientas hobby
 * de este tipo (no requiere API key). Pensado para uso personal moderado;
 * si Google empieza a bloquear por volumen, hay un backoff con reintentos.
 *
 * Cachea cada traducción exacta (mismo texto + mismo par de idiomas): en
 * proyectos de juego es normal repetir frases cientos de veces ("Sí", "No",
 * nombres de objetos, HP/MP, etc.), así que reutilizar el resultado ahorra
 * llamadas de red — más rápido y con menos oportunidades de que algo falle.
 * La caché se comparte entre todos los hilos que usan la misma instancia.
 */
class TranslationClient(
    private val sourceLang: String = "auto",
    private val targetLang: String = "es",
    initialCache: Map<String, String> = emptyMap()
) {
    class TranslationException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private val cache = ConcurrentHashMap<String, String>(initialCache)

    @Volatile
    private var cancelled = false

    /** Pide detener cuanto antes: las próximas llamadas a translate() fallan de inmediato (sin red). */
    fun cancel() { cancelled = true }

    /** Copia actual de la caché en memoria, para guardarla y reutilizarla en la siguiente corrida. */
    fun exportCache(): Map<String, String> = cache.toMap()

    /** Traduce un texto simple. Vacío o solo-espacios se devuelve tal cual (no gasta llamada). */
    fun translate(text: String): String {
        if (text.isBlank()) return text
        if (cancelled) throw TranslationException("cancelado por el usuario")

        cache[text]?.let { return it }

        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 4) {
            if (cancelled) throw TranslationException("cancelado por el usuario")
            try {
                val result = doRequest(text)
                cache[text] = result
                return result
            } catch (e: RateLimitException) {
                lastError = e
                attempt++
                // Google está limitando por volumen: esperamos bastante más que un error normal.
                Thread.sleep(1500L * attempt + (0..300).random())
            } catch (e: Exception) {
                lastError = e
                attempt++
                Thread.sleep(400L * attempt + (0..200).random()) // backoff con jitter
            }
        }
        throw TranslationException("Fallo al traducir tras 4 intentos: ${lastError?.message}", lastError)
    }

    private class RateLimitException(msg: String) : Exception(msg)

    private fun doRequest(text: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
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
        return sb.toString()
    }

    companion object {
        /**
         * Protege los códigos de control de RPG Maker (\V[1], \C[2], \N[3], \I[4], \., \|, \!, etc.)
         * reemplazándolos por marcadores @@N@@ antes de traducir, para que el traductor
         * automático no los altere. Devuelve el texto con marcadores y el mapa para restaurarlos.
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
                // El traductor a veces mete espacios alrededor del marcador; toleramos eso.
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
