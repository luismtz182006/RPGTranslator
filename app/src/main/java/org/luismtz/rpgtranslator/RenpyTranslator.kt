package org.luismtz.rpgtranslator

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Traduce los archivos .rpy de un proyecto Ren'Py trabajando línea por línea.
 * Solo procesa texto: NO copia imágenes, audio ni ningún otro asset — la
 * carpeta de salida solo contendrá los .rpy traducidos, replicando la
 * estructura de subcarpetas donde estaban.
 *
 * Procesa varios archivos .rpy EN PARALELO para que sea rápido. Si una línea
 * puntual falla al traducir, se deja tal cual en vez de perder el resto del
 * archivo.
 */
class RenpyTranslator(
    private val resolver: ContentResolver,
    private val client: TextTranslator,
    private val parallelism: Int = 6
) {
    data class Progress(val fileIndex: Int, val fileTotal: Int, val fileName: String, val lineIndex: Int, val lineTotal: Int)
    data class Warning(val fileName: String, val detail: String)

    private val dialogueRegex = Regex("""^(\s*)([A-Za-z_][A-Za-z0-9_.]*\s+)?"((?:[^"\\]|\\.)*)"(\s*)$""")
    private val choiceRegex = Regex("""^(\s*)"((?:[^"\\]|\\.)*)"(\s*):(\s*)$""")

    fun translateProject(
        sourceDir: DocumentFile,
        outputDir: DocumentFile,
        onProgress: (Progress) -> Unit,
        onWarning: (Warning) -> Unit = {}
    ): Pair<Int, Int> {
        val rpyFiles = ArrayList<DocumentFile>()
        collectRpyFiles(sourceDir, rpyFiles)

        val ok = AtomicInteger(0)
        val fail = AtomicInteger(0)
        val fileIndexCounter = AtomicInteger(0)
        val dirCache = HashMap<String, DocumentFile>()
        dirCache[""] = outputDir

        val pool = Executors.newFixedThreadPool(parallelism)
        try {
            val futures = rpyFiles.map { file ->
                pool.submit {
                    val name = file.name ?: "?"
                    try {
                        val fileIdx = fileIndexCounter.getAndIncrement()
                        val outDir = synchronized(dirCache) { resolveOutputDir(file, sourceDir, outputDir, dirCache) }
                        translateSingleFile(file, outDir, { detail -> onWarning(Warning(name, detail)) }) { lineIdx, lineTotal ->
                            onProgress(Progress(fileIdx, rpyFiles.size, name, lineIdx, lineTotal))
                        }
                        ok.incrementAndGet()
                    } catch (e: Exception) {
                        fail.incrementAndGet()
                        onWarning(Warning(name, "archivo completo falló: ${e.message}"))
                    }
                    Unit
                }
            }
            for (f in futures) f.get()
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.MINUTES)
        }
        return Pair(ok.get(), fail.get())
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

    private fun translateSingleFile(
        file: DocumentFile, outDir: DocumentFile,
        onWarning: (String) -> Unit, onLineProgress: (Int, Int) -> Unit
    ) {
        val lines = resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.readLines()
            ?: throw IllegalStateException("no se pudo leer ${file.name}")

        val outLines = ArrayList<String>(lines.size)
        for ((i, line) in lines.withIndex()) {
            outLines.add(translateLine(line, onWarning))
            onLineProgress(i + 1, lines.size)
        }

        FileCopier.writeTextFile(resolver, outDir, file.name ?: "script.rpy", "text/plain", outLines.joinToString("\n"))
    }

    private fun translateLine(line: String, onWarning: (String) -> Unit): String {
        dialogueRegex.matchEntire(line)?.let { m ->
            val (indent, speaker, text, trail) = m.destructured
            if (text.isBlank()) return line
            val translated = translateProtected(text, onWarning)
            return "$indent${speaker}\"$translated\"$trail"
        }
        choiceRegex.matchEntire(line)?.let { m ->
            val (indent, text, mid, trail) = m.destructured
            if (text.isBlank()) return line
            val translated = translateProtected(text, onWarning)
            return "$indent\"$translated\"$mid:$trail"
        }
        return line
    }

    private fun translateProtected(text: String, onWarning: (String) -> Unit): String {
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
