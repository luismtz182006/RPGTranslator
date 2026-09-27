package org.luismtz.rpgtranslator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Caché persistente de traducciones respaldada por SQLite. A diferencia del
 * viejo esquema (un JSON que solo se escribía al terminar toda la corrida),
 * esta caché se actualiza incrementalmente: cada [TranslatorConfig.cacheCheckpointEvery]
 * traducciones nuevas se hace un commit a disco. Si la app se cierra o truena
 * a la mitad, lo ya traducido no se pierde — la siguiente corrida retoma
 * desde ahí (mismo texto + mismo par de idiomas = mismo resultado en caché).
 */
class TranslationCacheDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "translation_cache.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cache (
                src_lang TEXT NOT NULL,
                tgt_lang TEXT NOT NULL,
                source_text TEXT NOT NULL,
                translated_text TEXT NOT NULL,
                PRIMARY KEY (src_lang, tgt_lang, source_text)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cache")
        onCreate(db)
    }

    /** Carga toda la caché existente para un par de idiomas (para precargar en memoria al iniciar). */
    fun loadAll(srcLang: String, tgtLang: String): Map<String, String> {
        val result = HashMap<String, String>()
        readableDatabase.rawQuery(
            "SELECT source_text, translated_text FROM cache WHERE src_lang = ? AND tgt_lang = ?",
            arrayOf(srcLang, tgtLang)
        ).use { cursor ->
            val srcIdx = cursor.getColumnIndexOrThrow("source_text")
            val tgtIdx = cursor.getColumnIndexOrThrow("translated_text")
            while (cursor.moveToNext()) {
                result[cursor.getString(srcIdx)] = cursor.getString(tgtIdx)
            }
        }
        return result
    }

    /** Guarda (o reemplaza) un lote de pares texto→traducción. Pensado para llamarse cada cierto número de traducciones. */
    fun saveBatch(srcLang: String, tgtLang: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((source, translated) in entries) {
                val values = ContentValues().apply {
                    put("src_lang", srcLang)
                    put("tgt_lang", tgtLang)
                    put("source_text", source)
                    put("translated_text", translated)
                }
                db.insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun countFor(srcLang: String, tgtLang: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cache WHERE src_lang = ? AND tgt_lang = ?",
            arrayOf(srcLang, tgtLang)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
