package org.luismtz.rpgtranslator

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Traduce un proyecto RPG Maker MV/MZ trabajando directamente sobre los JSON
 * de la carpeta /data (o /www/data en exportaciones viejas de MV).
 *
 * No modifica el proyecto original: escribe una copia completa en la carpeta
 * de salida, con los mismos archivos, mismas claves, mismo orden de datos —
 * solo cambia el texto traducible.
 */
class RpgMakerTranslator(
    private val resolver: ContentResolver,
    private val client: TranslationClient
) {
    data class Progress(val fileIndex: Int, val fileTotal: Int, val fileName: String, val unitIndex: Int, val unitTotal: Int)

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
        onProgress: (Progress) -> Unit
    ): Pair<Int, Int> {
        val files = dataDir.listFiles().filter { it.isFile && it.name?.endsWith(".json") == true }
        var translatedCount = 0
        var errorCount = 0

        for ((fileIdx, file) in files.withIndex()) {
            val name = file.name ?: continue
            try {
                val text = resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("no se pudo leer $name")

                val result: String = when {
                    name in databaseFiles -> processDatabaseArray(text, name) { u, t ->
                        onProgress(Progress(fileIdx, files.size, name, u, t))
                    }
                    name == "System.json" -> processSystemJson(text) { u, t ->
                        onProgress(Progress(fileIdx, files.size, name, u, t))
                    }
                    name == "CommonEvents.json" -> processCommonEvents(text) { u, t ->
                        onProgress(Progress(fileIdx, files.size, name, u, t))
                    }
                    name.startsWith("Map") && name.endsWith(".json") -> processMapFile(text) { u, t ->
                        onProgress(Progress(fileIdx, files.size, name, u, t))
                    }
                    else -> text // archivo no reconocido: se copia tal cual (MapInfos.json, etc.)
                }

                val outFile = outputDataDir.createFile("application/json", name)
                    ?: throw IllegalStateException("no se pudo crear $name en salida")
                resolver.openOutputStream(outFile.uri)?.use { it.write(result.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("no se pudo escribir $name")

                translatedCount++
            } catch (e: Exception) {
                errorCount++
            }
        }
        return Pair(translatedCount, errorCount)
    }

    private fun tr(text: String?): String? {
        if (text.isNullOrBlank()) return text
        val (protectedText, codes) = TranslationClient.protectControlCodes(text)
        val translated = client.translate(protectedText)
        return TranslationClient.restoreControlCodes(translated, codes)
    }

    // --- Actors/Classes/Skills/Items/Weapons/Armors/Enemies/States/Troops ---
    private fun processDatabaseArray(json: String, fileName: String, progress: (Int, Int) -> Unit): String {
        val arr = JSONArray(json)
        val fieldsToTranslate = when (fileName) {
            "Skills.json" -> listOf("name", "description", "message1", "message2")
            "Troops.json" -> listOf("name")
            else -> listOf("name", "description", "nickname", "profile")
        }
        var unitIndex = 0
        val total = arr.length() * fieldsToTranslate.size
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            for (field in fieldsToTranslate) {
                if (obj.has(field) && !obj.isNull(field)) {
                    val original = obj.optString(field, "")
                    if (original.isNotBlank()) {
                        obj.put(field, tr(original))
                    }
                }
                unitIndex++
                progress(unitIndex, total)
            }
        }
        return arr.toString()
    }

    // --- System.json: título, moneda, vocabulario, tipos ---
    private fun processSystemJson(json: String, progress: (Int, Int) -> Unit): String {
        val obj = JSONObject(json)
        var done = 0
        val approxTotal = 60 // estimado, solo para barra de progreso
        fun step() { done++; progress(done, approxTotal) }

        obj.optString("gameTitle").takeIf { it.isNotBlank() }?.let { obj.put("gameTitle", tr(it)); step() }
        obj.optString("currencyUnit").takeIf { it.isNotBlank() }?.let { obj.put("currencyUnit", tr(it)); step() }

        for (arrKey in listOf("armorTypes", "weaponTypes", "skillTypes", "equipTypes", "elements")) {
            val arr = obj.optJSONArray(arrKey) ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) { arr.put(i, tr(v)); step() }
            }
        }

        val terms = obj.optJSONObject("terms")
        if (terms != null) {
            for (key in listOf("basic", "commands", "params")) {
                val arr = terms.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) { arr.put(i, tr(v)); step() }
                }
            }
            val messages = terms.optJSONObject("messages")
            if (messages != null) {
                val keys = messages.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = messages.optString(k, "")
                    if (v.isNotBlank()) { messages.put(k, tr(v)); step() }
                }
            }
        }
        return obj.toString()
    }

    // --- CommonEvents.json: array de eventos, cada uno con "list" de comandos ---
    private fun processCommonEvents(json: String, progress: (Int, Int) -> Unit): String {
        val arr = JSONArray(json)
        var done = 0
        val total = arr.length().coerceAtLeast(1) * 10 // estimado
        for (i in 0 until arr.length()) {
            val ev = arr.optJSONObject(i) ?: continue
            val list = ev.optJSONArray("list") ?: continue
            translateCommandList(list) { done++; progress(done, total) }
        }
        return arr.toString()
    }

    // --- MapXXX.json: "events" -> cada uno "pages" -> cada una "list" ---
    private fun processMapFile(json: String, progress: (Int, Int) -> Unit): String {
        val obj = JSONObject(json)
        val events = obj.optJSONArray("events") ?: return obj.toString()
        var done = 0
        val total = events.length().coerceAtLeast(1) * 10 // estimado
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue // puede haber huecos null
            val pages = ev.optJSONArray("pages") ?: continue
            for (p in 0 until pages.length()) {
                val page = pages.optJSONObject(p) ?: continue
                val list = page.optJSONArray("list") ?: continue
                translateCommandList(list) { done++; progress(done, total) }
            }
        }
        return obj.toString()
    }

    /**
     * Recorre una lista de comandos de evento y traduce solo los códigos que
     * contienen texto de diálogo real:
     *  401 = línea de "Mostrar texto"
     *  405 = "Texto de desplazamiento"
     *  102 = opciones de "Mostrar opciones"
     * Todo lo demás (scripts, condicionales, comentarios) se deja intacto para
     * no romper la lógica del juego.
     */
    private fun translateCommandList(list: JSONArray, onUnit: () -> Unit) {
        for (i in 0 until list.length()) {
            val cmd = list.optJSONObject(i) ?: continue
            val code = cmd.optInt("code", -1)
            val params = cmd.optJSONArray("parameters") ?: continue
            when (code) {
                401, 405 -> {
                    val text = params.optString(0, "")
                    if (text.isNotBlank()) {
                        params.put(0, tr(text))
                        onUnit()
                    }
                }
                102 -> {
                    val choices = params.optJSONArray(0) ?: continue
                    for (c in 0 until choices.length()) {
                        val choiceText = choices.optString(c, "")
                        if (choiceText.isNotBlank()) {
                            choices.put(c, tr(choiceText))
                        }
                    }
                    onUnit()
                }
            }
        }
    }
}
