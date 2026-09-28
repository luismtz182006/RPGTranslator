package org.luismtz.rpgtranslator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * Ejecuta la traducción completa como trabajo de WorkManager, promovido a
 * foreground service (con notificación) para que sobreviva a que la app se
 * cierre o pase a segundo plano — importante en fabricantes con gestión de
 * batería agresiva (Xiaomi/HyperOS, Samsung), que de otro modo matarían el
 * proceso a los pocos segundos de minimizar la app.
 *
 * El progreso y el log se exponen vía [androidx.work.WorkInfo] observado
 * desde la Activity — no hay UI aquí, este worker no sabe nada de pantallas.
 */
class TranslationWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_PROJECT_URI = "project_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ENGINE = "engine" // "rpgmaker" | "renpy"
        const val KEY_SRC_LANG = "src_lang"
        const val KEY_TGT_LANG = "tgt_lang"
        const val KEY_OFFLINE = "offline"

        const val KEY_PROGRESS_LABEL = "progress_label"
        const val KEY_LOG_TEXT = "log_text"

        const val KEY_RESULT_OK = "result_ok"
        const val KEY_RESULT_FAIL = "result_fail"
        const val KEY_RESULT_ERROR = "result_error"

        const val CHANNEL_ID = "rpg_translator_progress"
        const val NOTIFICATION_ID = 4201

        const val MAX_LOG_LINES = 200
    }

    private val logLines = ArrayDeque<String>()
    @Volatile private var currentLabel: String = ""
    private val lastPublishMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastNotifyMs = java.util.concurrent.atomic.AtomicLong(0L)

    /** Últimos caracteres del log (WorkManager limita cada Data a ~10KB, así que se recorta). */
    private fun currentLogText(): String =
        synchronized(logLines) { logLines.joinToString("\n") }.takeLast(6000)

    /**
     * Publica log + etiqueta juntos (cada setProgress reemplaza al anterior, así que van en un solo Data).
     * Con throttle para no saturar WorkManager, ya que se llama por cada frase traducida.
     */
    private fun publish() {
        val now = System.currentTimeMillis()
        val last = lastPublishMs.get()
        if (now - last < 500 || !lastPublishMs.compareAndSet(last, now)) return
        try {
            setProgressAsync(
                Data.Builder()
                    .putString(KEY_LOG_TEXT, currentLogText())
                    .putString(KEY_PROGRESS_LABEL, currentLabel.takeLast(300))
                    .build()
            )
        } catch (_: Exception) { }
    }

    private fun log(msg: String) {
        synchronized(logLines) {
            logLines.addLast(msg)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
        publish()
    }

    private fun updateLabel(label: String) {
        currentLabel = label
        publish()
    }

    /** Actualiza la notificación (máx. una vez cada ~1.5s); nunca debe tumbar la traducción. */
    private fun updateNotification(text: String) {
        val now = System.currentTimeMillis()
        val last = lastNotifyMs.get()
        if (now - last < 1500 || !lastNotifyMs.compareAndSet(last, now)) return
        try { setForegroundAsync(createForegroundInfo(text)) } catch (_: Exception) { }
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("Preparando…"))

        val projectUri = inputData.getString(KEY_PROJECT_URI)?.let { Uri.parse(it) }
            ?: return Result.failure(errorData("falta la carpeta del proyecto"))
        val outputUri = inputData.getString(KEY_OUTPUT_URI)?.let { Uri.parse(it) }
            ?: return Result.failure(errorData("falta la carpeta de salida"))
        val engine = inputData.getString(KEY_ENGINE) ?: "rpgmaker"
        val srcLang = inputData.getString(KEY_SRC_LANG) ?: "auto"
        val tgtLang = inputData.getString(KEY_TGT_LANG) ?: "es"
        val offline = inputData.getBoolean(KEY_OFFLINE, false)

        val resolver = applicationContext.contentResolver
        val projectRoot = DocumentFile.fromTreeUri(applicationContext, projectUri)
            ?: return Result.failure(errorData("no se pudo abrir la carpeta del proyecto"))
        val outputRoot = DocumentFile.fromTreeUri(applicationContext, outputUri)
            ?: return Result.failure(errorData("no se pudo abrir la carpeta de salida"))

        val cacheDb = TranslationCacheDb(applicationContext)
        var offlineClientRef: OfflineTranslationClient? = null
        val startTime = System.currentTimeMillis()

        // Idioma de origen efectivo: si srcLang es "auto" y se detecta, aquí queda el detectado.
        // Se usa para guardar/cargar la caché bajo el idioma real, no bajo "auto".
        var effectiveSrcLang = srcLang

        return try {
            val client: TextTranslator = if (offline) {
                log("Preparando traductor local ($srcLang → $tgtLang)…")
                val oc = OfflineTranslationClient(srcLang, tgtLang)
                offlineClientRef = oc
                updateLabel("Descargando modelo de idioma…")
                log("Descargando modelo de idioma si hace falta (una sola vez, puede tardar)…")
                oc.ensureModelDownloaded()
                log("Modelo listo. Traduciendo sin conexión…\n")
                oc
            } else {
                // 1) Detectar idioma si hace falta
                if (srcLang.equals("auto", ignoreCase = true)) {
                    val sample = findSampleText(projectRoot, engine)
                    if (sample != null) {
                        val probe = TranslationClient(srcLang, tgtLang, emptyMap())
                        val detected = probe.detectLanguage(sample)
                        if (detected != null) {
                            effectiveSrcLang = detected
                            log("Idioma de origen detectado: $detected (se usará para toda la corrida en vez de 'auto').\n")
                        }
                    }
                }

                // 2) Cargar caché bajo el idioma efectivo; si difiere del original, unir la del original
                val savedCache = cacheDb.loadAll(effectiveSrcLang, tgtLang).toMutableMap()
                if (effectiveSrcLang != srcLang) {
                    cacheDb.loadAll(srcLang, tgtLang).forEach { (k, v) -> savedCache.putIfAbsent(k, v) }
                }
                if (savedCache.isNotEmpty()) log("Reutilizando ${savedCache.size} traducción(es) ya hechas antes.\n")

                TranslationClient(effectiveSrcLang, tgtLang, savedCache)
            }

            fun checkpoint() {
                val pending = client.pendingCacheEntries()
                if (pending.isNotEmpty() && !offline) {
                    cacheDb.saveBatch(effectiveSrcLang, tgtLang, pending)
                    client.clearPendingCacheEntries()
                }
            }

            val (ok, fail) = if (engine == "renpy") {
                log("Traduciendo archivos .rpy…\n")
                val translator = RenpyTranslator(resolver, client, onCheckpoint = ::checkpoint)
                translator.translateProject(
                    projectRoot, outputRoot,
                    onProgress = { p ->
                        updateLabel("${p.currentFile} — ${p.unitsDone} traducidas")
                        updateNotification("Traduciendo… (${p.unitsDone})")
                    },
                    onWarning = { w -> log("⚠ ${w.fileName}: ${w.detail}") }
                )
            } else {
                val translator = RpgMakerTranslator(resolver, client, onCheckpoint = ::checkpoint)
                val dataDir = translator.findDataFolder(projectRoot)
                    ?: return Result.failure(errorData("no se encontró una carpeta 'data' (ni 'www/data') en el proyecto"))
                val outDataDir = outputRoot.findFile("data")?.takeIf { it.isDirectory }
                    ?: outputRoot.createDirectory("data")
                    ?: return Result.failure(errorData("no se pudo preparar la carpeta de salida"))

                log("Traduciendo archivos de datos…\n")
                translator.translateProject(
                    dataDir, outDataDir,
                    onProgress = { p ->
                        updateLabel("${p.currentFile} — ${p.unitsDone} traducidas")
                        updateNotification("Traduciendo… (${p.unitsDone})")
                    },
                    onWarning = { w -> log("⚠ ${w.fileName}: ${w.detail}") }
                )
            }

            checkpoint()
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
            log("\nListo: $ok archivo(s) traducido(s), $fail con error de archivo completo.")
            log("Tiempo total: ${elapsedSec}s.")

            Result.success(
                Data.Builder()
                    .putInt(KEY_RESULT_OK, ok)
                    .putInt(KEY_RESULT_FAIL, fail)
                    .putString(KEY_LOG_TEXT, currentLogText())
                    .build()
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // la cancelación cooperativa debe propagarse, no tragarse
        } catch (e: Exception) {
            log("✘ Error: ${e.message}")
            Result.failure(errorData(e.message ?: "error desconocido"))
        } finally {
            offlineClientRef?.close()
        }
    }

    private fun errorData(msg: String): Data = Data.Builder()
        .putString(KEY_RESULT_ERROR, msg)
        .putString(KEY_LOG_TEXT, currentLogText())
        .build()

    /** Busca una frase corta cualquiera del proyecto, solo para detectar el idioma de origen una vez. */
    private fun findSampleText(projectRoot: DocumentFile, engine: String): String? {
        return try {
            if (engine == "renpy") {
                val rpy = findFirstFileRecursive(projectRoot) { it.name?.endsWith(".rpy") == true } ?: return null
                val text = applicationContext.contentResolver.openInputStream(rpy.uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
                Regex(""""((?:[^"\\]|\\.){4,80})"""").find(text)?.groupValues?.get(1)
            } else {
                val dataDir = projectRoot.findFile("data")?.takeIf { it.isDirectory }
                    ?: projectRoot.findFile("www")?.findFile("data")?.takeIf { it.isDirectory }
                    ?: return null
                val systemJson = dataDir.findFile("System.json")
                if (systemJson != null) {
                    val text = applicationContext.contentResolver.openInputStream(systemJson.uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    val title = text?.let { org.json.JSONObject(it).optString("gameTitle", "") }
                    if (!title.isNullOrBlank()) return title
                }
                val anyFile = dataDir.listFiles().firstOrNull { it.name?.endsWith(".json") == true } ?: return null
                val text = applicationContext.contentResolver.openInputStream(anyFile.uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
                Regex(""""name"\s*:\s*"((?:[^"\\]|\\.){2,60})"""").find(text)?.groupValues?.get(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findFirstFileRecursive(dir: DocumentFile, predicate: (DocumentFile) -> Boolean): DocumentFile? {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                findFirstFileRecursive(child, predicate)?.let { return it }
            } else if (predicate(child)) {
                return child
            }
        }
        return null
    }


    private fun createForegroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Progreso de traducción", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("RPG Translator")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
