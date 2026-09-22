package org.luismtz.rpgtranslator

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

    private lateinit var rgEngine: RadioGroup
    private lateinit var rbRenpy: RadioButton
    private lateinit var tvProjectDir: TextView
    private lateinit var tvOutputDir: TextView
    private lateinit var etSourceLang: TextInputEditText
    private lateinit var etTargetLang: TextInputEditText
    private lateinit var btnTranslate: Button
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvStatus: TextView

    private val pickProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                projectDirUri = uri
                tvProjectDir.text = uri.path ?: uri.toString()
            }
        }

    private val pickOutputLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                outputDirUri = uri
                tvOutputDir.text = uri.path ?: uri.toString()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rgEngine = findViewById(R.id.rgEngine)
        rbRenpy = findViewById(R.id.rbRenpy)
        tvProjectDir = findViewById(R.id.tvProjectDir)
        tvOutputDir = findViewById(R.id.tvOutputDir)
        etSourceLang = findViewById(R.id.etSourceLang)
        etTargetLang = findViewById(R.id.etTargetLang)
        btnTranslate = findViewById(R.id.btnTranslate)
        progressBar = findViewById(R.id.progressBar)
        tvProgressLabel = findViewById(R.id.tvProgressLabel)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnPickProject).setOnClickListener { pickProjectLauncher.launch(null) }
        findViewById<Button>(R.id.btnPickOutput).setOnClickListener { pickOutputLauncher.launch(null) }
        btnTranslate.setOnClickListener { startTranslation() }
    }

    private fun log(msg: String) {
        runOnUiThread { tvStatus.append(msg + "\n") }
    }

    private fun setUiEnabled(enabled: Boolean) {
        btnTranslate.isEnabled = enabled
        btnTranslate.text = if (enabled) "Traducir" else "Traduciendo…"
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
        val client = TranslationClient(srcLang, tgtLang)

        val projectRoot = DocumentFile.fromTreeUri(this, projUri)
        val outputRoot = DocumentFile.fromTreeUri(this, outUri)
        if (projectRoot == null || outputRoot == null) {
            log("No se pudieron abrir las carpetas elegidas")
            return
        }

        progressBar.visibility = LinearProgressIndicator.VISIBLE
        progressBar.progress = 0
        setUiEnabled(false)

        Thread {
            try {
                if (rbRenpy.isChecked) {
                    runRenpy(projectRoot, outputRoot, client)
                } else {
                    runRpgMaker(projectRoot, outputRoot, client)
                }
            } catch (e: Exception) {
                log("✘ Error inesperado: ${e.message}")
            } finally {
                runOnUiThread {
                    progressBar.visibility = LinearProgressIndicator.GONE
                    setUiEnabled(true)
                }
            }
        }.start()
    }

    private fun runRpgMaker(projectRoot: DocumentFile, outputRoot: DocumentFile, client: TranslationClient) {
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

    private fun runRenpy(projectRoot: DocumentFile, outputRoot: DocumentFile, client: TranslationClient) {
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
