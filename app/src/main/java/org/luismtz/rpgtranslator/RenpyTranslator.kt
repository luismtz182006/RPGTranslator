package org.luismtz.rpgtranslator

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

/**
 * Traduce los archivos .rpy de un proyecto Ren'Py trabajando línea por línea.
 *
 * Reconoce dos formas comunes:
 *   personaje "texto de diálogo"
 *   "texto sin personaje (narrador)"
 * y las opciones dentro de un bloque menu::
 *   "texto de la opción":
 *
 * Todo lo demás (python:, $ variable = ..., label, jump, call, define, image,
 * show/hide/scene, comentarios) se copia tal cual, para no romper la lógica
 * del juego. Los tags de Ren'Py ([variable], {tag}) se protegen antes de
 * traducir para que no se alteren.
 */
class RenpyTranslator(
    private val resolver: ContentResolver,
    private val client: TranslationClient
) {
    data class Progress(val fileIndex: Int, val fileTotal: Int, val fileName: String, val lineIndex: Int, val lineTotal: Int)

    private val dialogueRegex = Regex("""^(\s*)([A-Za-z_][A-Za-z0-9_.]*\s+)?"((?:[^"\\]|\\.)*)"(\s*)$""")
    private val choiceRegex = Regex("""^(\s*)"((?:[^"\\]|\\.)*)"(\s*):(\s*)$""")

    /** Recorre recursivamente el proyecto: traduce .rpy, copia todo lo demás tal cual. */
    fun translateProject(sourceDir: DocumentFile, outputDir: DocumentFile, onProgress: (Progress) -> Unit): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        val rpyFiles = ArrayList<DocumentFile>()
        collectRpyFiles(sourceDir, rpyFiles)

        for ((idx, file) in rpyFiles.withIndex()) {
            try {
                translateSingleFile(file, sourceDir, outputDir) { lineIdx, lineTotal ->
                    onProgress(Progress(idx, rpyFiles.size, file.name ?: "?", lineIdx, lineTotal))
                }
                ok++
            } catch (e: Exception) {
                fail++
            }
        }
        return Pair(ok, fail)
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

    /** Encuentra (creando si hace falta) la carpeta de salida que replica la ruta relativa del archivo. */
    private fun resolveOutputDir(file: DocumentFile, sourceRoot: DocumentFile, outputRoot: DocumentFile): DocumentFile {
        val relPath = relativePath(file.parentFile, sourceRoot)
        var current = outputRoot
        for (seg in relPath) {
            current = current.findFile(seg)?.takeIf { it.isDirectory } ?: current.createDirectory(seg)!!
        }
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
        file: DocumentFile,
        sourceRoot: DocumentFile,
        outputRoot: DocumentFile,
        onLineProgress: (Int, Int) -> Unit
    ) {
        val lines = resolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.readLines()
            ?: throw IllegalStateException("no se pudo leer ${file.name}")

        val outLines = ArrayList<String>(lines.size)
        for ((i, line) in lines.withIndex()) {
            outLines.add(translateLine(line))
            onLineProgress(i + 1, lines.size)
        }

        val outDir = resolveOutputDir(file, sourceRoot, outputRoot)
        val outFile = outDir.createFile("text/plain", file.name ?: "script.rpy")
            ?: throw IllegalStateException("no se pudo crear ${file.name} en salida")
        resolver.openOutputStream(outFile.uri)?.use { os ->
            os.write(outLines.joinToString("\n").toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("no se pudo escribir ${file.name}")
    }

    private fun translateLine(line: String): String {
        dialogueRegex.matchEntire(line)?.let { m ->
            val (indent, speaker, text, trail) = m.destructured
            if (text.isBlank()) return line
            val translated = translateProtected(text)
            return "$indent${speaker}\"$translated\"$trail"
        }
        choiceRegex.matchEntire(line)?.let { m ->
            val (indent, text, mid, trail) = m.destructured
            if (text.isBlank()) return line
            val translated = translateProtected(text)
            return "$indent\"$translated\"$mid:$trail"
        }
        return line
    }

    private fun translateProtected(text: String): String {
        val (protectedText, tags) = TranslationClient.protectRenpyTags(text)
        val translated = client.translate(protectedText)
        return TranslationClient.restoreRenpyTags(translated, tags)
    }
}
