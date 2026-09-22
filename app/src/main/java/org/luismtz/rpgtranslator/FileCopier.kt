package org.luismtz.rpgtranslator

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

/**
 * Copia recursivamente todo el contenido de [source] hacia [dest], preservando
 * la estructura de subcarpetas. Si un archivo ya existe en destino, se
 * reemplaza (se borra y se vuelve a crear) en vez de duplicarse.
 *
 * [skipTopLevelNames] permite saltar carpetas/archivos de primer nivel (por
 * ejemplo "data", que se traduce aparte y no debe copiarse tal cual).
 */
object FileCopier {

    fun copyRecursively(
        resolver: ContentResolver,
        source: DocumentFile,
        dest: DocumentFile,
        skipTopLevelNames: Set<String> = emptySet(),
        isTopLevel: Boolean = true,
        onFile: (String) -> Unit = {}
    ) {
        for (child in source.listFiles()) {
            val name = child.name ?: continue
            if (isTopLevel && name in skipTopLevelNames) continue

            if (child.isDirectory) {
                val childDest = dest.findFile(name)?.takeIf { it.isDirectory }
                    ?: dest.createDirectory(name)
                    ?: continue
                copyRecursively(resolver, child, childDest, emptySet(), isTopLevel = false, onFile = onFile)
            } else {
                try {
                    copySingleFile(resolver, child, dest, name)
                    onFile(name)
                } catch (_: Exception) {
                    // Un archivo suelto que falle no debe tumbar la copia completa.
                }
            }
        }
    }

    /** Copia (reemplazando si ya existe) un solo archivo hacia la carpeta [destDir], con nombre [name]. */
    fun copySingleFile(resolver: ContentResolver, source: DocumentFile, destDir: DocumentFile, name: String) {
        destDir.findFile(name)?.delete()
        val mime = source.type ?: "application/octet-stream"
        val outFile = destDir.createFile(mime, name) ?: throw IllegalStateException("no se pudo crear $name")
        resolver.openInputStream(source.uri)?.use { input ->
            resolver.openOutputStream(outFile.uri)?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("no se pudo escribir $name")
        } ?: throw IllegalStateException("no se pudo leer $name")
    }

    /** Crea (reemplazando si ya existe) un archivo de texto con [content] dentro de [destDir]. */
    fun writeTextFile(resolver: ContentResolver, destDir: DocumentFile, name: String, mime: String, content: String) {
        destDir.findFile(name)?.delete()
        val outFile = destDir.createFile(mime, name) ?: throw IllegalStateException("no se pudo crear $name")
        resolver.openOutputStream(outFile.uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("no se pudo escribir $name")
    }
}
