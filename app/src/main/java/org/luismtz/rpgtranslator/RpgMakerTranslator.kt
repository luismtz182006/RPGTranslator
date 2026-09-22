package org.luismtz.rpgtranslator

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Traduce un proyecto RPG Maker MV/MZ trabajando directamente sobre los JSON
 * de la carpeta /data (o /www/data en exportaciones viejas de MV).
 *
 * No modifica el proyecto original: escribe una copia completa en la carpeta
 * de salida, con los mismos archivos, mismas claves, mismo orden de datos —
 * solo cambia el texto traducible.
 *
 * Procesa varios archivos JSON EN PARALELO (varias conexiones de traducción
 * simultáneas) para no tardar una eternidad en proyectos grandes. Si una
 * frase puntual falla al traducir, se deja el texto original en su lugar en
 * vez de tirar todo el archivo — así un error de red aislado no hace perder
 * el resto del trabajo ya hecho.
 */
class RpgMakerTranslator(
    private val resolver: ContentResolver,
    private val client: TranslationClient,
    private val parallelism: Int = 6
) {
    data class Progress(val fileIndex: Int, val fileTotal: Int, val fileName: String, val unitIndex: Int, val unitTotal: Int)
    data class Warning(val fileName: String, val detail: String)

    private val databaseFiles = setOf(
        "Actors.json", "Classes.json", "Skills.json", "Items.json",
        "Weapons.json", "Armors.json", "Enemies.json", "States.json", "Troops.json"
    )

    /** Busca la carpeta data/ dentro del proyecto (soporta estructura MV vieja con www/). */
    fun findDataFolder(root: DocumentFile): DocumentFile? {
        root.findFile("data")?.let { if (it.isDirectory) return it }
        root.findFile("www")?.findFile("data")?.let { if (it.isDirectory) return it }
        return null
    }

    fun translateProject(
        dataDir: DocumentFile,
        outputDataDir: DocumentFile,
        onProgress: (Progress) -> Unit,
        onWarning: (Warning) -> Unit = {}
    ): Pair<Int, Int> {
        val files = dataDir.listFiles().filter { it.isFile && it.name?.endsWith(".json") == true }
        val translatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val fileIndexCounter = AtomicInteger(0)

        val pool = Executors.newFixedThreadPool(parallelism)
        try {
            val futures = files.map { file ->
                pool.submit {
                    val name = file.name ?: return@submit
                    try {
                        val text = resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: throw IllegalStateException("no se pudo leer $name")

                        val fileIdx = fileIndexCounter.getAndIncrement()
                        fun reportProgress(u: Int, t: Int) = onProgress(Progress(fileIdx, files.size, name, u, t))
                        fun reportWarning(detail: String) = onWarning(Warning(name, detail))

                        val result: String = when {
                            name in databaseFiles -> processDatabaseArray(text, name, ::reportProgress, ::reportWarning)
                            name == "System.json" -> processSystemJson(text, ::reportProgress, ::reportWarning)
                            name == "CommonEvents.json" -> processCommonEvents(text, ::reportProgress, ::reportWarning)
                            name.startsWith("Map") && name.endsWith(".json") -> processMapFile(text, ::reportProgress, ::reportWarning)
                            else -> text
                        }

                        synchronized(outputDataDir) {
                            FileCopier.writeTextFile(resolver, outputDataDir, name, "application/json", result)
                        }
                        translatedCount.incrementAndGet()
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                        onWarning(Warning(name, "archivo completo falló: ${e.message}"))
                    }
                    Unit
                }
            }
            for (f in futures) f.get() // espera a que todos terminen
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.MINUTES)
        }
        return Pair(translatedCount.get(), errorCount.get())
    }

    /** Traduce un texto protegiendo códigos de control; si falla, deja el original y avisa (no tumba el resto). */
    private fun tr(text: String?, onWarning: (String) -> Unit): String? {
        if (text.isNullOrBlank()) return text
        val (protectedText, codes) = TranslationClient.protectControlCodes(text)
        return try {
            val translated = client.translate(protectedText)
            TranslationClient.restoreControlCodes(translated, codes)
        } catch (e: Exception) {
            onWarning("no se pudo traducir \"${text.take(40)}\": ${e.message}")
            text // se conserva el original en vez de perder el resto del archivo
        }
    }

    private fun processDatabaseArray(
        json: String, fileName: String,
        progress: (Int, Int) -> Unit, warn: (String) -> Unit
    ): String {
        val arr = JSONArray(json)
        val fieldsToTranslate = when (fileName) {
            "Skills.json" -> listOf("name", "description", "message1", "message2")
            "Troops.json" -> listOf("name")
            else -> listOf("name", "description", "nickname", "profile")
        }
        var unitIndex = 0
        val total = (arr.length() * fieldsToTranslate.size).coerceAtLeast(1)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            for (field in fieldsToTranslate) {
                if (obj.has(field) && !obj.isNull(field)) {
                    val original = obj.optString(field, "")
                    if (original.isNotBlank()) {
                        obj.put(field, tr(original, warn))
                    }
                }
                unitIndex++
                progress(unitIndex, total)
            }
        }
        return arr.toString()
    }

    private fun processSystemJson(json: String, progress: (Int, Int) -> Unit, warn: (String) -> Unit): String {
        val obj = JSONObject(json)
        var done = 0
        val approxTotal = 60
        fun step() { done++; progress(done, approxTotal) }

        obj.optString("gameTitle").takeIf { it.isNotBlank() }?.let { obj.put("gameTitle", tr(it, warn)); step() }
        obj.optString("currencyUnit").takeIf { it.isNotBlank() }?.let { obj.put("currencyUnit", tr(it, warn)); step() }

        for (arrKey in listOf("armorTypes", "weaponTypes", "skillTypes", "equipTypes", "elements")) {
            val arr = obj.optJSONArray(arrKey) ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) { arr.put(i, tr(v, warn)); step() }
            }
        }

        val terms = obj.optJSONObject("terms")
        if (terms != null) {
            for (key in listOf("basic", "commands", "params")) {
                val arr = terms.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) { arr.put(i, tr(v, warn)); step() }
                }
            }
            val messages = terms.optJSONObject("messages")
            if (messages != null) {
                val keys = messages.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = messages.optString(k, "")
                    if (v.isNotBlank()) { messages.put(k, tr(v, warn)); step() }
                }
            }
        }
        return obj.toString()
    }

    private fun processCommonEvents(json: String, progress: (Int, Int) -> Unit, warn: (String) -> Unit): String {
        val arr = JSONArray(json)
        var done = 0
        val total = arr.length().coerceAtLeast(1) * 10
        for (i in 0 until arr.length()) {
            val ev = arr.optJSONObject(i) ?: continue
            val list = ev.optJSONArray("list") ?: continue
            translateCommandList(list, warn) { done++; progress(done, total) }
        }
        return arr.toString()
    }

    private fun processMapFile(json: String, progress: (Int, Int) -> Unit, warn: (String) -> Unit): String {
        val obj = JSONObject(json)
        val events = obj.optJSONArray("events") ?: return obj.toString()
        var done = 0
        val total = events.length().coerceAtLeast(1) * 10
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val pages = ev.optJSONArray("pages") ?: continue
            for (p in 0 until pages.length()) {
                val page = pages.optJSONObject(p) ?: continue
                val list = page.optJSONArray("list") ?: continue
                translateCommandList(list, warn) { done++; progress(done, total) }
            }
        }
        return obj.toString()
    }

    private fun translateCommandList(list: JSONArray, warn: (String) -> Unit, onUnit: () -> Unit) {
        for (i in 0 until list.length()) {
            val cmd = list.optJSONObject(i) ?: continue
            val code = cmd.optInt("code", -1)
            val params = cmd.optJSONArray("parameters") ?: continue
            when (code) {
                401, 405 -> {
                    val text = params.optString(0, "")
                    if (text.isNotBlank()) {
                        params.put(0, tr(text, warn))
                        onUnit()
                    }
                }
                102 -> {
                    val choices = params.optJSONArray(0) ?: continue
                    for (c in 0 until choices.length()) {
                        val choiceText = choices.optString(c, "")
                        if (choiceText.isNotBlank()) {
                            choices.put(c, tr(choiceText, warn))
                        }
                    }
                    onUnit()
                }
            }
        }
    }
}
