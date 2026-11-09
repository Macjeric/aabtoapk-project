package tz.aab2apk

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import tz.aab2apk.core.AabConverter
import tz.aab2apk.core.ConvertOptions
import tz.aab2apk.core.SigningKeys
import java.io.File

class MainActivity : Activity() {

    private lateinit var fileLabel: TextView
    private lateinit var abiCheck: CheckBox
    private lateinit var convertBtn: Button
    private lateinit var installBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private var baseName = "app"
    private var inputReady = false
    private var outputApk: File? = null

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm != null) this@MainActivity.startActivity(confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> log("✔ Installed.")
                else -> log("✘ Install did not complete: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason") +
                    "\nIf the app is already installed with a different signature, uninstall it first.")
            }
        }
    }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AAB to APK"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "AAB → APK"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Turns an Android App Bundle (.aab) into one installable universal APK, signed on this phone."
            textSize = 14f
            setPadding(0, dp(6), 0, dp(14))
        })
        root.addView(button("1. Choose .aab file") { pickFile() })
        fileLabel = TextView(this).apply { text = "No file selected"; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(fileLabel)
        abiCheck = CheckBox(this).apply { text = "Smaller APK: keep only this phone's CPU types" }
        root.addView(abiCheck)
        convertBtn = button("2. Convert") { startConvert() }.apply { isEnabled = false }
        root.addView(convertBtn)
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress)
        installBtn = button("3. Install APK") { startInstall() }.apply { isEnabled = false }
        root.addView(installBtn)
        saveBtn = button("Save APK to Downloads") { saveApk() }.apply { isEnabled = false }
        root.addView(saveBtn)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(logView)

        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        val filter = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(installReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(installReceiver) } catch (_: Exception) {}
    }

    private fun log(msg: String) = runOnUiThread {
        logView.append(msg + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(busy: Boolean) = runOnUiThread {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        convertBtn.isEnabled = !busy && inputReady
    }

    // ------------------------------------------------------------------ pick
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = displayName(uri) ?: "bundle.aab"
        baseName = name.substringBeforeLast('.', name)
        fileLabel.text = name
        inputReady = false
        installBtn.isEnabled = false
        saveBtn.isEnabled = false
        convertBtn.isEnabled = false
        logView.text = ""
        setBusy(true)
        log("Reading $name …")
        Thread {
            try {
                val dst = File(cacheDir, "input.aab")
                contentResolver.openInputStream(uri)!!.use { ins -> dst.outputStream().use { ins.copyTo(it, 1 shl 16) } }
                inputReady = true
                log("Ready (${dst.length() / 1024} KB). Tap Convert.")
            } catch (e: Exception) {
                log("Could not read the file: ${e.message}")
            }
            setBusy(false)
        }.start()
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // --------------------------------------------------------------- convert
    private fun startConvert() {
        val keepAbis = if (abiCheck.isChecked) Build.SUPPORTED_ABIS.toSet() else null
        outputApk = null
        installBtn.isEnabled = false
        saveBtn.isEnabled = false
        setBusy(true)
        logView.text = ""
        Thread {
            try {
                val outDir = File(filesDir, "out").apply { deleteRecursively(); mkdirs() }
                val out = File(outDir, "$baseName-universal.apk")
                val keys = SigningKeys.loadOrCreate(File(filesDir, "signing"))
                val t0 = System.currentTimeMillis()
                val r = AabConverter.convert(
                    File(cacheDir, "input.aab"), out, File(cacheDir, "work"), keys,
                    ConvertOptions(keepAbis)
                ) { log(it) }
                r.warnings.forEach { log("⚠ $it") }
                log("✔ Done in ${(System.currentTimeMillis() - t0) / 1000.0}s — ${out.length() / 1024} KB")
                r.packageName?.let { log("Package: $it") }
                log("Signing key SHA-256:\n${keys.sha256Fingerprint()}")
                outputApk = out
                runOnUiThread { installBtn.isEnabled = true; saveBtn.isEnabled = true }
            } catch (e: Throwable) {
                log("✘ Conversion failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            setBusy(false)
        }.start()
    }

    // ---------------------------------------------------------------- save
    private fun saveApk() {
        val apk = outputApk ?: return
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create file in Downloads")
                    contentResolver.openOutputStream(uri)!!.use { os -> apk.inputStream().use { it.copyTo(os, 1 shl 16) } }
                    log("Saved to Downloads/${apk.name}")
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    val dst = File(dir, apk.name)
                    apk.copyTo(dst, overwrite = true)
                    log("Saved to ${dst.absolutePath}")
                }
            } catch (e: Exception) {
                log("✘ Save failed: ${e.message}")
            }
        }.start()
    }

    // ------------------------------------------------------------- install
    private fun startInstall() {
        val apk = outputApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            log("Allow this app to install apps, then come back and tap Install again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        Thread {
            try {
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setSize(apk.length())
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    apk.inputStream().use { ins ->
                        session.openWrite("base.apk", 0, apk.length()).use { os ->
                            ins.copyTo(os, 1 shl 16)
                            session.fsync(os)
                        }
                    }
                    val intent = Intent(ACTION_INSTALL_RESULT).setPackage(packageName)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(this, id, intent, flags)
                    session.commit(pi.intentSender)
                }
                log("Install requested — confirm on the system screen.")
            } catch (e: Exception) {
                log("✘ Install failed: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val ACTION_INSTALL_RESULT = "tz.aab2apk.INSTALL_RESULT"
    }
}
