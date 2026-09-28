package org.luismtz.rpgtranslator

import android.content.ContentResolver
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Traduce un proyecto RPG Maker MV/MZ trabajando directamente sobre los JSON
 * de la carpeta /data (o /www/data en exportaciones viejas de MV).
 *
 * No modifica el proyecto original: escribe una copia completa en la carpeta
 * de salida, con los mismos archivos, mismas claves, mismo orden de datos —
 * solo cambia el texto traducible.
 *
 * Procesa varios archivos JSON en paralelo (acotado por [TranslatorConfig.fileConcurrency]),
 * usando corrutinas en vez de hilos crudos — la cancelación es cooperativa y
 * limpia. Si una frase puntual falla al traducir, se deja el texto original
 * en su lugar en vez de tirar todo el archivo.
 */
class RpgMakerTranslator(
    private val resolver: ContentResolver,
    private val client: TextTranslator,
    private val config: TranslatorConfig = TranslatorConfig.DEFAULT,
    private val onCheckpoint: () -> Unit = {}
) {
    companion object {
        private const val TAG = "RpgMakerTranslator"
    }

    data class Progress(val unitsDone: Int, val unitsTotal: Int, val currentFile: String)
    data class Warning(val fileName: String, val detail: String)

    private val databaseFields = mapOf(
        "Skills.json" to listOf("name", "description", "message1", "message2"),
        "States.json" to listOf("name", "message1", "message2", "message3", "message4"),
        "Actors.json" to listOf("name", "description", "nickname", "profile"),
        "Classes.json" to listOf("name", "description"),
        "Items.json" to listOf("name", "description"),
        "Weapons.json" to listOf("name", "description"),
        "Armors.json" to listOf("name", "description"),
        "Enemies.json" to listOf("name", "description")
    )

    /** Busca la carpeta data/ dentro del proyecto (soporta estructura MV vieja con www/). */
    fun findDataFolder(root: DocumentFile): DocumentFile? {
        root.findFile("data")?.let { if (it.isDirectory) return it }
        root.findFile("www")?.findFile("data")?.let { if (it.isDirectory) return it }
        return null
    }

    suspend fun translateProject(
        dataDir: DocumentFile,
        outputDataDir: DocumentFile,
        onProgress: (Progress) -> Unit,
        onWarning: (Warning) -> Unit = {}
    ): Pair<Int, Int> = coroutineScope {
        val files = dataDir.listFiles().filter { it.isFile && it.name?.endsWith(".json") == true }
        val translatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val unitsDone = AtomicInteger(0)
        val unitsSinceCheckpoint = AtomicInteger(0)

        val fileSemaphore = Semaphore(config.fileConcurrency)

        val deferreds = files.map { file ->
            async {
                fileSemaphore.withPermit {
                    val name = file.name ?: return@withPermit
                    try {
                        val text = resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: throw IllegalStateException("no se pudo leer $name")

                        fun reportUnit() {
                            val done = unitsDone.incrementAndGet()
                            onProgress(Progress(done, -1, name))
                            if (unitsSinceCheckpoint.incrementAndGet() >= config.cacheCheckpointEvery) {
                                unitsSinceCheckpoint.set(0)
                                onCheckpoint()
                            }
                        }
                        fun reportWarning(detail: String) {
                            Log.w(TAG, "$name: $detail")
                            onWarning(Warning(name, detail))
                        }

                        val result: String = when {
                            name == "Troops.json" -> processTroopsJson(text, ::reportUnit, ::reportWarning)
                            databaseFields.containsKey(name) -> processDatabaseArray(text, databaseFields.getValue(name), ::reportUnit, ::reportWarning)
                            name == "System.json" -> processSystemJson(text, ::reportUnit, ::reportWarning)
                            name == "CommonEvents.json" -> processCommandListJson(text, isMap = false, ::reportUnit, ::reportWarning)
                            name.startsWith("Map") && name.endsWith(".json") -> processCommandListJson(text, isMap = true, ::reportUnit, ::reportWarning)
                            else -> text
                        }

                        FileCopier.writeTextFile(resolver, outputDataDir, name, "application/json", result)
                        translatedCount.incrementAndGet()
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                        Log.e(TAG, "$name: archivo completo falló", e)
                        onWarning(Warning(name, "archivo completo falló: ${e.message}"))
                    }
                }
            }
        }
        deferreds.awaitAll()
        onCheckpoint() // checkpoint final, por si quedó algo pendiente sin llegar al umbral
        Pair(translatedCount.get(), errorCount.get())
    }

    private suspend fun tr(text: String?, onWarning: (String) -> Unit): String? {
        if (text.isNullOrBlank()) return text
        val (protectedText, codes) = TranslationClient.protectControlCodes(text)
        return try {
            val translated = client.translate(protectedText)
            TranslationClient.restoreControlCodes(translated, codes)
        } catch (e: Exception) {
            onWarning("no se pudo traducir \"${text.take(40)}\": ${e.message}")
            text
        }
    }

    private suspend fun processDatabaseArray(
        json: String, fieldsToTranslate: List<String>,
        onUnit: () -> Unit, warn: (String) -> Unit
    ): String {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            for (field in fieldsToTranslate) {
                if (obj.has(field) && !obj.isNull(field)) {
                    val original = obj.optString(field, "")
                    if (original.isNotBlank()) {
                        obj.put(field, tr(original, warn))
                        onUnit()
                    }
                }
            }
        }
        return arr.toString()
    }

    /**
     * Traduce Troops.json: el nombre de la tropa + los diálogos de batalla
     * dentro de pages[].list[] (mismos códigos que en los eventos de mapa).
     */
    private suspend fun processTroopsJson(json: String, onUnit: () -> Unit, warn: (String) -> Unit): String {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val troop = arr.optJSONObject(i) ?: continue

            val name = troop.optString("name", "")
            if (name.isNotBlank()) {
                troop.put("name", tr(name, warn))
                onUnit()
            }

            val pages = troop.optJSONArray("pages") ?: continue
            for (p in 0 until pages.length()) {
                val page = pages.optJSONObject(p) ?: continue
                val list = page.optJSONArray("list") ?: continue
                translateCommandList(list, onUnit, warn)
            }
        }
        return arr.toString()
    }

    private suspend fun processSystemJson(json: String, onUnit: () -> Unit, warn: (String) -> Unit): String {
        val obj = JSONObject(json)

        obj.optString("gameTitle").takeIf { it.isNotBlank() }?.let { obj.put("gameTitle", tr(it, warn)); onUnit() }
        obj.optString("currencyUnit").takeIf { it.isNotBlank() }?.let { obj.put("currencyUnit", tr(it, warn)); onUnit() }

        for (arrKey in listOf("armorTypes", "weaponTypes", "skillTypes", "equipTypes", "elements")) {
            val arr = obj.optJSONArray(arrKey) ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) { arr.put(i, tr(v, warn)); onUnit() }
            }
        }

        val terms = obj.optJSONObject("terms")
        if (terms != null) {
            for (key in listOf("basic", "commands", "params")) {
                val arr = terms.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) { arr.put(i, tr(v, warn)); onUnit() }
                }
            }
            val messages = terms.optJSONObject("messages")
            if (messages != null) {
                val keys = messages.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = messages.optString(k, "")
                    if (v.isNotBlank()) { messages.put(k, tr(v, warn)); onUnit() }
                }
            }
        }
        return obj.toString()
    }

    /** Procesa CommonEvents.json (isMap=false) o un MapXXX.json (isMap=true). */
    private suspend fun processCommandListJson(json: String, isMap: Boolean, onUnit: () -> Unit, warn: (String) -> Unit): String {
        if (isMap) {
            val obj = JSONObject(json)
            val events = obj.optJSONArray("events") ?: return obj.toString()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue // puede haber huecos null
                val pages = ev.optJSONArray("pages") ?: continue
                for (p in 0 until pages.length()) {
                    val page = pages.optJSONObject(p) ?: continue
                    val list = page.optJSONArray("list") ?: continue
                    translateCommandList(list, onUnit, warn)
                }
            }
            return obj.toString()
        } else {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val ev = arr.optJSONObject(i) ?: continue
                val list = ev.optJSONArray("list") ?: continue
                translateCommandList(list, onUnit, warn)
            }
            return arr.toString()
        }
    }

    /**
     * Recorre una lista de comandos de evento y traduce solo los códigos con
     * texto de diálogo real:
     *  401/405 = línea de "Mostrar texto" / continuación
     *  102     = opciones de "Mostrar opciones"
     *  402     = eco del texto de una opción en la rama "When [choice]"
     *  105     = cabecera de "Texto de desplazamiento"
     * Todo lo demás (scripts, condicionales, comentarios) se deja intacto para
     * no romper la lógica del juego.
     */
    private suspend fun translateCommandList(list: JSONArray, onUnit: () -> Unit, warn: (String) -> Unit) {
        for (i in 0 until list.length()) {
            val cmd = list.optJSONObject(i) ?: continue
            val code = cmd.optInt("code", -1)
            val params = cmd.optJSONArray("parameters") ?: continue
            when (code) {
                401, 405, 105 -> {
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
                402 -> {
                    // parameters[1] suele ser una copia del texto de la opción correspondiente
                    if (params.length() > 1) {
                        val echoText = params.optString(1, "")
                        if (echoText.isNotBlank()) {
                            params.put(1, tr(echoText, warn))
                            onUnit()
                        }
                    }
                }
            }
        }
    }
}
