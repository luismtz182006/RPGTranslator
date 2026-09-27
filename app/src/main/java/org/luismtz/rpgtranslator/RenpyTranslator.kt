package org.luismtz.rpgtranslator

import android.content.ContentResolver
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Traduce los archivos .rpy de un proyecto Ren'Py trabajando línea por línea.
 *
 * **Limitación conocida:** el reconocimiento de líneas de diálogo usa
 * expresiones regulares, no un parser/AST real del lenguaje de Ren'Py. Cubre
 * el caso común (`personaje "texto"` / `"texto":` en menús) pero no diálogo
 * multi-línea, concatenación de strings, ni bloques ATL complejos. Escribir
 * un parser completo del lenguaje sería un proyecto aparte de mucho mayor
 * alcance.
 *
 * Solo procesa texto: NO copia imágenes, audio ni ningún otro asset.
 * Procesa varios archivos .rpy en paralelo (corrutinas, acotado por
 * [TranslatorConfig.fileConcurrency]).
 */
class RenpyTranslator(
    private val resolver: ContentResolver,
    private val client: TextTranslator,
    private val config: TranslatorConfig = TranslatorConfig.DEFAULT,
    private val onCheckpoint: () -> Unit = {}
) {
    companion object {
        private const val TAG = "RenpyTranslator"
    }

    data class Progress(val unitsDone: Int, val currentFile: String)
    data class Warning(val fileName: String, val detail: String)

    suspend fun translateProject(
        sourceDir: DocumentFile,
        outputDir: DocumentFile,
        onProgress: (Progress) -> Unit,
        onWarning: (Warning) -> Unit = {}
    ): Pair<Int, Int> = coroutineScope {
        val rpyFiles = ArrayList<DocumentFile>()
        collectRpyFiles(sourceDir, rpyFiles)

        val ok = AtomicInteger(0)
        val fail = AtomicInteger(0)
        val unitsDone = AtomicInteger(0)
        val unitsSinceCheckpoint = AtomicInteger(0)
        val dirCache = java.util.Collections.synchronizedMap(HashMap<String, DocumentFile>())
        dirCache[""] = outputDir

        val fileSemaphore = Semaphore(config.fileConcurrency)

        val deferreds = rpyFiles.map { file ->
            async {
                fileSemaphore.withPermit {
                    val name = file.name ?: "?"
                    try {
                        val outDir = synchronized(dirCache) { resolveOutputDir(file, sourceDir, outputDir, dirCache) }
                        translateSingleFile(
                            file, outDir,
                            onWarning = { detail ->
                                Log.w(TAG, "$name: $detail")
                                onWarning(Warning(name, detail))
                            },
                            onLineUnit = {
                                onProgress(Progress(unitsDone.incrementAndGet(), name))
                                if (unitsSinceCheckpoint.incrementAndGet() >= config.cacheCheckpointEvery) {
                                    unitsSinceCheckpoint.set(0)
                                    onCheckpoint()
                                }
                            }
                        )
                        ok.incrementAndGet()
                    } catch (e: Exception) {
                        fail.incrementAndGet()
                        Log.e(TAG, "$name: archivo completo falló", e)
                        onWarning(Warning(name, "archivo completo falló: ${e.message}"))
                    }
                }
            }
        }
        deferreds.awaitAll()
        onCheckpoint()
        Pair(ok.get(), fail.get())
    }

    private fun collectRpyFiles(dir: DocumentFile, out: MutableList<DocumentFile>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                collectRpyFiles(child, out)
            } else if (child.name?.endsWith(".rpy") == true) {
                out.add(child)
            }
        }
    }

    /** Encuentra (creando si hace falta) la carpeta de salida que replica la ruta relativa del .rpy. Cachea resultados. */
    private fun resolveOutputDir(
        file: DocumentFile, sourceRoot: DocumentFile, outputRoot: DocumentFile,
        cache: MutableMap<String, DocumentFile>
    ): DocumentFile {
        val relPath = relativePath(file.parentFile, sourceRoot)
        val key = relPath.joinToString("/")
        cache[key]?.let { return it }

        var current = outputRoot
        var accumKey = ""
        for (seg in relPath) {
            accumKey = if (accumKey.isEmpty()) seg else "$accumKey/$seg"
            current = cache[accumKey] ?: run {
                val existing = current.findFile(seg)
                val d = if (existing != null && existing.isDirectory) existing else current.createDirectory(seg)!!
                cache[accumKey] = d
                d
            }
        }
        cache[key] = current
        return current
    }

    private fun relativePath(dir: DocumentFile?, root: DocumentFile): List<String> {
        val segments = ArrayList<String>()
        var current = dir
        while (current != null && current.uri != root.uri) {
            segments.add(0, current.name ?: "")
            current = current.parentFile
        }
        return segments
    }

    private suspend fun translateSingleFile(
        file: DocumentFile, outDir: DocumentFile,
        onWarning: (String) -> Unit, onLineUnit: () -> Unit
    ) {
        val lines = resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.readLines()
            ?: throw IllegalStateException("no se pudo leer ${file.name}")

        val outLines = ArrayList<String>(lines.size)
        for (line in lines) {
            outLines.add(translateLine(line, onWarning, onLineUnit))
        }

        FileCopier.writeTextFile(resolver, outDir, file.name ?: "script.rpy", "text/plain", outLines.joinToString("\n"))
    }

    private suspend fun translateLine(line: String, onWarning: (String) -> Unit, onUnit: () -> Unit): String {
        RenpyLineParser.matchDialogue(line)?.let { m ->
            if (m.text.isBlank()) return line
            val translated = translateProtected(m.text, onWarning)
            onUnit()
            return "${m.indent}${m.speaker}\"$translated\"${m.trail}"
        }
        RenpyLineParser.matchChoice(line)?.let { m ->
            if (m.text.isBlank()) return line
            val translated = translateProtected(m.text, onWarning)
            onUnit()
            return "${m.indent}\"$translated\"${m.mid}:${m.trail}"
        }
        return line
    }

    private suspend fun translateProtected(text: String, onWarning: (String) -> Unit): String {
        val (protectedText, tags) = TranslationClient.protectRenpyTags(text)
        return try {
            val translated = client.translate(protectedText)
            TranslationClient.restoreRenpyTags(translated, tags)
        } catch (e: Exception) {
            onWarning("no se pudo traducir \"${text.take(40)}\": ${e.message}")
            text
        }
    }
}
