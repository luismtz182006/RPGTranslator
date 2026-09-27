package org.luismtz.rpgtranslator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private var projectDirUri: Uri? = null
    private var outputDirUri: Uri? = null
    private var currentWorkId: UUID? = null

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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* si se niega, la traducción igual corre, solo sin notificación visible */ }

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
        requestNotificationPermissionIfNeeded()
        reattachToRunningWork()

        findViewById<Button>(R.id.btnPickProject).setOnClickListener { pickProjectLauncher.launch(null) }
        findViewById<Button>(R.id.btnPickOutput).setOnClickListener { pickOutputLauncher.launch(null) }
        btnTranslate.setOnClickListener { startTranslation() }
        btnCancel.setOnClickListener {
            currentWorkId?.let { WorkManager.getInstance(this).cancelWorkById(it) }
            btnCancel.isEnabled = false
            btnCancel.text = "Cancelando…"
        }
        btnCopyLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RPG Translator log", tvStatus.text.toString()))
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Si ya había una traducción corriendo en background (WorkManager) al reabrir la app, la reengancha. */
    private fun reattachToRunningWork() {
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(WORK_NAME).observe(this) { infos ->
            val info = infos?.firstOrNull() ?: return@observe
            if (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED) {
                currentWorkId = info.id
                observeWork(info.id)
                setUiEnabled(false)
                progressBar.visibility = LinearProgressIndicator.VISIBLE
            }
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

    private fun setUiEnabled(enabled: Boolean) {
        btnTranslate.isEnabled = enabled
        btnTranslate.text = if (enabled) "Traducir" else "Traduciendo…"
        btnCancel.visibility = if (enabled) Button.GONE else Button.VISIBLE
        btnCancel.isEnabled = true
        btnCancel.text = "Cancelar"
    }

    private fun startTranslation() {
        tvStatus.text = ""
        tvProgressLabel.text = ""
        val projUri = projectDirUri
        val outUri = outputDirUri
        if (projUri == null || outUri == null) {
            Toast.makeText(this, "Elige la carpeta del proyecto y la de salida", Toast.LENGTH_SHORT).show()
            return
        }

        val srcLang = etSourceLang.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "auto"
        val tgtLang = etTargetLang.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "es"
        val offline = rbOffline.isChecked
        val engine = if (rbRenpy.isChecked) "renpy" else "rpgmaker"

        if (offline && srcLang.equals("auto", ignoreCase = true)) {
            Toast.makeText(this, "El modo local no soporta 'auto': escribe el idioma de origen real (ej. 'ja')", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putString(keySrcLang, srcLang)
            .putString(keyTgtLang, tgtLang)
            .putString(keyEngine, engine)
            .apply()

        val inputData = Data.Builder()
            .putString(TranslationWorker.KEY_PROJECT_URI, projUri.toString())
            .putString(TranslationWorker.KEY_OUTPUT_URI, outUri.toString())
            .putString(TranslationWorker.KEY_ENGINE, engine)
            .putString(TranslationWorker.KEY_SRC_LANG, srcLang)
            .putString(TranslationWorker.KEY_TGT_LANG, tgtLang)
            .putBoolean(TranslationWorker.KEY_OFFLINE, offline)
            .build()

        val request = OneTimeWorkRequestBuilder<TranslationWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        currentWorkId = request.id
        progressBar.visibility = LinearProgressIndicator.VISIBLE
        progressBar.isIndeterminate = true
        setUiEnabled(false)
        observeWork(request.id)
    }

    private fun observeWork(id: UUID) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info == null) return@observe

            info.progress.getString(TranslationWorker.KEY_LOG_TEXT)?.let { tvStatus.text = it }
            info.progress.getString(TranslationWorker.KEY_PROGRESS_LABEL)?.let { tvProgressLabel.text = it }

            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val ok = info.outputData.getInt(TranslationWorker.KEY_RESULT_OK, 0)
                    val fail = info.outputData.getInt(TranslationWorker.KEY_RESULT_FAIL, 0)
                    Toast.makeText(this, "Listo: $ok ok, $fail con error", Toast.LENGTH_SHORT).show()
                    finishWork()
                }
                WorkInfo.State.FAILED -> {
                    val err = info.outputData.getString(TranslationWorker.KEY_RESULT_ERROR) ?: "error desconocido"
                    tvStatus.append("\n✘ $err")
                    finishWork()
                }
                WorkInfo.State.CANCELLED -> {
                    tvStatus.append("\nCancelado.")
                    finishWork()
                }
                else -> { /* ENQUEUED / RUNNING / BLOCKED: seguir esperando */ }
            }
        }
    }

    private fun finishWork() {
        progressBar.visibility = LinearProgressIndicator.GONE
        progressBar.isIndeterminate = false
        setUiEnabled(true)
        currentWorkId = null
    }

    companion object {
        private const val WORK_NAME = "rpg_translation_work"
    }
}
