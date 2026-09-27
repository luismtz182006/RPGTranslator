package org.luismtz.rpgtranslator

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

/** Utilidad para escribir archivos de texto en la carpeta de salida, reemplazando si ya existían. */
object FileCopier {

    /** Crea (reemplazando si ya existe) un archivo de texto con [content] dentro de [destDir]. */
    fun writeTextFile(resolver: ContentResolver, destDir: DocumentFile, name: String, mime: String, content: String) {
        destDir.findFile(name)?.delete()
        val outFile = destDir.createFile(mime, name) ?: throw IllegalStateException("no se pudo crear $name")
        resolver.openOutputStream(outFile.uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("no se pudo escribir $name")
    }
}
