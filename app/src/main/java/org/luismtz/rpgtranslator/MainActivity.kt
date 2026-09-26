package org.luismtz.rpgtranslator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private var projectDirUri: Uri? = null
    private var outputDirUri: Uri? = null
    private var activeClient: TextTranslator? = null

    private val prefsName = "rpg_translator_prefs"
    private val keyProjectDir = "project_dir_uri"
    private val keyOutputDir = "output_dir_uri"
    private val keySrcLang = "src_lang"
    private val keyTgtLang = "tgt_lang"
    private val keyEngine = "engine"
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var rgEngine: RadioGroup
    private lateinit var rbRenpy: RadioButton
    private lateinit var rbRpgMaker: RadioButton
    private lateinit var rgMode: RadioGroup
    private lateinit var rbOffline: RadioButton
    private lateinit var tvProjectDir: TextView
    private lateinit var tvOutputDir: TextView
    private lateinit var etSourceLang: TextInputEditText
    private lateinit var etTargetLang: TextInputEditText
    private lateinit var btnTranslate: Button
    private lateinit var btnCancel: Button
    private lateinit var btnCopyLog: Button
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvStatus: TextView

    private val pickProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                projectDirUri = uri
                tvProjectDir.text = uri.path ?: uri.toString()
                prefs.edit().putString(keyProjectDir, uri.toString()).apply()
            }
        }

    private val pickOutputLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                outputDirUri = uri
                tvOutputDir.text = uri.path ?: uri.toString()
                prefs.edit().putString(keyOutputDir, uri.toString()).apply()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        rgEngine = findViewById(R.id.rgEngine)
        rbRenpy = findViewById(R.id.rbRenpy)
        rbRpgMaker = findViewById(R.id.rbRpgMaker)
        rgMode = findViewById(R.id.rgMode)
        rbOffline = findViewById(R.id.rbOffline)
        tvProjectDir = findViewById(R.id.tvProjectDir)
        tvOutputDir = findViewById(R.id.tvOutputDir)
        etSourceLang = findViewById(R.id.etSourceLang)
        etTargetLang = findViewById(R.id.etTargetLang)
        btnTranslate = findViewById(R.id.btnTranslate)
        btnCancel = findViewById(R.id.btnCancel)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        progressBar = findViewById(R.id.progressBar)
        tvProgressLabel = findViewById(R.id.tvProgressLabel)
        tvStatus = findViewById(R.id.tvStatus)

        restoreSavedSettings()

        findViewById<Button>(R.id.btnPickProject).setOnClickListener { pickProjectLauncher.launch(null) }
        findViewById<Button>(R.id.btnPickOutput).setOnClickListener { pickOutputLauncher.launch(null) }
        btnTranslate.setOnClickListener { startTranslation() }
        btnCancel.setOnClickListener {
            activeClient?.cancel()
            btnCancel.isEnabled = false
            btnCancel.text = "Cancelando…"
        }
        btnCopyLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RPG Translator log", tvStatus.text.toString()))
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
        }
    }

    /** Restaura carpetas e idiomas usados la última vez, si los permisos siguen vigentes. */
    private fun restoreSavedSettings() {
        prefs.getString(keySrcLang, null)?.let { etSourceLang.setText(it) }
        prefs.getString(keyTgtLang, null)?.let { etTargetLang.setText(it) }
        if (prefs.getString(keyEngine, "rpgmaker") == "renpy") rgEngine.check(R.id.rbRenpy) else rgEngine.check(R.id.rbRpgMaker)

        prefs.getString(keyProjectDir, null)?.let { saved ->
            try {
                val uri = Uri.parse(saved)
                if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                    projectDirUri = uri
                    tvProjectDir.text = uri.path ?: uri.toString()
                }
            } catch (_: Exception) { }
        }
        prefs.getString(keyOutputDir, null)?.let { saved ->
            try {
                val uri = Uri.parse(saved)
                if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
                    outputDirUri = uri
                    tvOutputDir.text = uri.path ?: uri.toString()
                }
            } catch (_: Exception) { }
        }
    }

    private fun log(msg: String) {
        runOnUiThread { tvStatus.append(msg + "\n") }
    }

    private fun setUiEnabled(enabled: Boolean) {
        btnTranslate.isEnabled = enabled
        btnTranslate.text = if (enabled) "Traducir" else "Traduciendo…"
        btnCancel.visibility = if (enabled) Button.GONE else Button.VISIBLE
        btnCancel.isEnabled = true
        btnCancel.text = "Cancelar"
    }

    private fun startTranslation() {
        tvStatus.text = ""
        val projUri = projectDirUri
        val outUri = outputDirUri
        if (projUri == null || outUri == null) {
            Toast.makeText(this, "Elige la carpeta del proyecto y la de salida", Toast.LENGTH_SHORT).show()
            return
        }

        val srcLang = etSourceLang.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "auto"
        val tgtLang = etTargetLang.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "es"
        val offline = rbOffline.isChecked

        if (offline && srcLang.equals("auto", ignoreCase = true)) {
            Toast.makeText(this, "El modo local no soporta 'auto': escribe el idioma de origen real (ej. 'ja')", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putString(keySrcLang, srcLang)
            .putString(keyTgtLang, tgtLang)
            .putString(keyEngine, if (rbRenpy.isChecked) "renpy" else "rpgmaker")
            .apply()

        val projectRoot = DocumentFile.fromTreeUri(this, projUri)
        val outputRoot = DocumentFile.fromTreeUri(this, outUri)
        if (projectRoot == null || outputRoot == null) {
            log("No se pudieron abrir las carpetas elegidas")
            return
        }

        progressBar.visibility = LinearProgressIndicator.VISIBLE
        progressBar.progress = 0
        setUiEnabled(false)

        val startTime = System.currentTimeMillis()

        Thread {
            var offlineClientRef: OfflineTranslationClient? = null
            try {
                val client: TextTranslator = if (offline) {
                    log("Preparando traductor local ($srcLang → $tgtLang)…")
                    val oc = OfflineTranslationClient(srcLang, tgtLang)
                    offlineClientRef = oc
                    activeClient = oc
                    log("Descargando modelo de idioma si hace falta (una sola vez, puede tardar)…")
                    oc.ensureModelDownloaded()
                    log("Modelo listo. Traduciendo sin conexión…\n")
                    oc
                } else {
                    val savedCache = TranslationCacheStore.load(this, srcLang, tgtLang)
                    if (savedCache.isNotEmpty()) log("Reutilizando ${savedCache.size} traducción(es) ya hechas antes.\n")
                    val tc = TranslationClient(srcLang, tgtLang, savedCache)
                    activeClient = tc
                    tc
                }

                if (rbRenpy.isChecked) {
                    runRenpy(projectRoot, outputRoot, client)
                } else {
                    runRpgMaker(projectRoot, outputRoot, client)
                }

                if (!offline) {
                    TranslationCacheStore.save(this, srcLang, tgtLang, client.exportCache())
                }
            } catch (e: Exception) {
                log("✘ Error: ${e.message}")
            } finally {
                offlineClientRef?.close()
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
                log("Tiempo total: ${elapsedSec}s.")
                activeClient = null
                runOnUiThread {
                    progressBar.visibility = LinearProgressIndicator.GONE
                    setUiEnabled(true)
                }
            }
        }.start()
    }

    private fun runRpgMaker(projectRoot: DocumentFile, outputRoot: DocumentFile, client: TextTranslator) {
        val translator = RpgMakerTranslator(contentResolver, client)
        val dataDir = translator.findDataFolder(projectRoot)
        if (dataDir == null) {
            log("✘ No se encontró una carpeta 'data' (ni 'www/data') dentro del proyecto elegido.")
            return
        }

        log("Traduciendo archivos de datos (varios en paralelo)…\n")
        val outDataDir = outputRoot.findFile("data")?.takeIf { it.isDirectory }
            ?: outputRoot.createDirectory("data")
            ?: run { log("✘ No se pudo preparar la carpeta de salida"); return }

        val (ok, fail) = translator.translateProject(
            dataDir, outDataDir,
            onProgress = { p ->
                runOnUiThread {
                    tvProgressLabel.text = "[archivo ${p.fileIndex + 1}/${p.fileTotal}] ${p.fileName} — ${p.unitIndex}/${p.unitTotal}"
                    progressBar.max = p.fileTotal
                    progressBar.progress = p.fileIndex
                }
            },
            onWarning = { w -> log("⚠ ${w.fileName}: ${w.detail}") }
        )
        log("\nListo: $ok archivo(s) traducido(s), $fail con error de archivo completo.")
        log("Solo se tradujo el texto — copia tú las carpetas de imágenes/audio/js si las necesitas en la salida.")
    }

    private fun runRenpy(projectRoot: DocumentFile, outputRoot: DocumentFile, client: TextTranslator) {
        val translator = RenpyTranslator(contentResolver, client)
        log("Traduciendo archivos .rpy (varios en paralelo)…\n")
        val (ok, fail) = translator.translateProject(
            projectRoot, outputRoot,
            onProgress = { p ->
                runOnUiThread {
                    tvProgressLabel.text = "[archivo ${p.fileIndex + 1}/${p.fileTotal}] ${p.fileName} — línea ${p.lineIndex}/${p.lineTotal}"
                    progressBar.max = p.fileTotal
                    progressBar.progress = p.fileIndex
                }
            },
            onWarning = { w -> log("⚠ ${w.fileName}: ${w.detail}") }
        )
        log("\nListo: $ok archivo(s) .rpy traducido(s), $fail con error de archivo completo.")
        log("Solo se tradujo el texto — copia tú las imágenes/audio si las necesitas en la salida.")
    }
}
