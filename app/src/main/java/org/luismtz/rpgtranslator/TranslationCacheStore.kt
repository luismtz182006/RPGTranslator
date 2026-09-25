package org.luismtz.rpgtranslator

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Guarda la caché de traducciones en almacenamiento interno de la app (no
 * requiere permisos), separada por par de idiomas. Así, si vuelves a
 * traducir el mismo proyecto (o uno distinto con frases repetidas) en otra
 * sesión, no se vuelve a gastar red en lo que ya se tradujo antes.
 */
object TranslationCacheStore {

    private fun fileFor(context: Context, sourceLang: String, targetLang: String): File {
        val safe = "${sourceLang}_$targetLang".replace(Regex("[^A-Za-z0-9_]"), "_")
        return File(context.filesDir, "translation_cache_$safe.json")
    }

    fun load(context: Context, sourceLang: String, targetLang: String): Map<String, String> {
        return try {
            val file = fileFor(context, sourceLang, targetLang)
            if (!file.exists()) return emptyMap()
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val map = HashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(context: Context, sourceLang: String, targetLang: String, cache: Map<String, String>) {
        try {
            val obj = JSONObject()
            for ((k, v) in cache) obj.put(k, v)
            fileFor(context, sourceLang, targetLang).writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Si falla el guardado, no es crítico — simplemente no se aprovecha la caché la próxima vez.
        }
    }
}
