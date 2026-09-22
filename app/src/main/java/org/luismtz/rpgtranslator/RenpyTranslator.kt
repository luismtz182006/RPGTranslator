package org.luismtz.rpgtranslator

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Traduce los archivos .rpy de un proyecto Ren'Py trabajando línea por línea,
 * y copia todo lo demás (imágenes, audio, rpyc, etc.) tal cual para que la
 * salida sea un proyecto completo y jugable.
 *
 * Procesa varios archivos .rpy EN PARALELO. Si una línea puntual falla al
 * traducir, se deja tal cual (no se traduce esa línea) en vez de perder el
 * resto del archivo.
 */
class RenpyTranslator(
    private val resolver: ContentResolver,
    private val client: TranslationClient,
    private val parallelism: Int = 6
) {
    data class Progress(val fileIndex: Int, val fileTotal: Int, val fileName: String, val lineIndex: Int, val lineTotal: Int)
    data class Warning(val fileName: String, val detail: String)

    private val dialogueRegex = Regex("""^(\s*)([A-Za-z_][A-Za-z0-9_.]*\s+)?"((?:[^"\\]|\\.)*)"(\s*)$""")
    private val choiceRegex = Regex("""^(\s*)"((?:[^"\\]|\\.)*)"(\s*):(\s*)$""")

    /**
     * Copia primero TODO el proyecto tal cual (imágenes, audio, etc.), y
     * luego traduce y sobreescribe únicamente los .rpy en la carpeta de salida.
     */
    fun translateProject(
        sourceDir: DocumentFile,
        outputDir: DocumentFile,
        onCopyProgress: (String) -> Unit = {},
        onProgress: (Progress) -> Unit,
        onWarning: (Warning) -> Unit = {}
    ): Pair<Int, Int> {
        // 1. Copia completa del proyecto (todo, incluyendo los .rpy sin traducir todavía)
        FileCopier.copyRecursively(resolver, sourceDir, outputDir, onFile = onCopyProgress)

        // 2. Localiza los .rpy ya copiados en la salida y los traduce ahí mismo, en paralelo
        val rpyFiles = ArrayList<DocumentFile>()
        collectRpyFiles(outputDir, rpyFiles)

        val ok = AtomicInteger(0)
        val fail = AtomicInteger(0)
        val fileIndexCounter = AtomicInteger(0)

        val pool = Executors.newFixedThreadPool(parallelism)
        try {
            val futures = rpyFiles.map { file ->
                pool.submit {
                    val name = file.name ?: "?"
                    try {
                        val fileIdx = fileIndexCounter.getAndIncrement()
                        translateSingleFileInPlace(file) { lineIdx, lineTotal ->
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

    /** Traduce un .rpy ya copiado en destino, reemplazando su propio contenido en el mismo lugar. */
    private fun translateSingleFileInPlace(file: DocumentFile, onLineProgress: (Int, Int) -> Unit) {
        val lines = resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.readLines()
            ?: throw IllegalStateException("no se pudo leer ${file.name}")

        val warnings = StringBuilder()
        val outLines = ArrayList<String>(lines.size)
        for ((i, line) in lines.withIndex()) {
            outLines.add(translateLine(line) { warnings.append(it).append('\n') })
            onLineProgress(i + 1, lines.size)
        }

        resolver.openOutputStream(file.uri, "wt")?.use { os ->
            os.write(outLines.joinToString("\n").toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("no se pudo escribir ${file.name}")
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
