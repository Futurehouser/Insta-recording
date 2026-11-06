package com.futurehouser.instarecording

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var limit: CheckBox
    private lateinit var audio: CheckBox
    private lateinit var fps60: RadioButton
    private lateinit var fullButton: Button
    private lateinit var areaButton: Button
    private lateinit var statusLabel: TextView
    private lateinit var openButton: Button
    private lateinit var checkButton: Button
    private lateinit var updateButton: Button
    private var pickArea = false
    private var update: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        checkForUpdates(manual = false)
        handleInstallStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val idle = !RecordService.running
        fullButton.isEnabled = idle
        areaButton.isEnabled = idle
        val status = RecordService.status
        if (status.isNotEmpty()) statusLabel.text = status
        else if (!idle) statusLabel.text = "A recording is in progress."
        openButton.visibility = if (RecordService.savedVideo != null) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }
        val gap = { top: Int ->
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
        }
        root.addView(TextView(this).apply {
            text = "Insta-recording"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Record your screen → 1080×1920 MP4 for Instagram Reels"
            setTextColor(0xFF666666.toInt())
        })

        val rates = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rates.addView(TextView(this).apply {
            text = "Frame rate:"
            setTextColor(Color.BLACK)
            setPadding(0, 0, dp(12), 0)
        })
        fps60 = RadioButton(this).apply { text = "60"; id = View.generateViewId() }
        val fps30 = RadioButton(this).apply { text = "30"; id = View.generateViewId() }
        rates.addView(fps60)
        rates.addView(fps30)
        rates.check(if (prefs.getInt("fps", 60) == 60) fps60.id else fps30.id)
        rates.setOnCheckedChangeListener { _, _ ->
            prefs.edit().putInt("fps", if (fps60.isChecked) 60 else 30).apply()
        }
        root.addView(rates, gap(16))

        audio = setting("Record system audio", "audio", true)
        limit = setting("Stop automatically at 59 seconds", "limit_59", false)
        root.addView(audio, gap(4))
        root.addView(limit, gap(4))

        fullButton = Button(this).apply {
            text = "Full screen"
            setOnClickListener { record(area = false) }
        }
        areaButton = Button(this).apply {
            text = "Select area"
            setOnClickListener { record(area = true) }
        }
        val buttons = LinearLayout(this)
        val half = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        buttons.addView(areaButton, half())
        buttons.addView(fullButton, half())
        root.addView(buttons, gap(20))

        statusLabel = TextView(this).apply {
            text = "Ready. Choose how much of the screen to record."
            setTextColor(Color.BLACK)
        }
        root.addView(statusLabel, gap(14))
        openButton = Button(this).apply {
            text = "Open video"
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(RecordService.savedVideo, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        root.addView(openButton, gap(8))

        updateButton = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { installUpdate() }
        }
        root.addView(updateButton, gap(20))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, dp(12), 0)
        })
        checkButton = Button(this).apply {
            text = "Check for updates"
            setOnClickListener { checkForUpdates(manual = true) }
        }
        footer.addView(checkButton)
        root.addView(footer, gap(8))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
    }

    private fun setting(label: String, key: String, default: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = prefs.getBoolean(key, default)
        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    // ---------- Recording ----------
    private fun record(area: Boolean) {
        pickArea = area
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("One permission needed")
                .setMessage("Insta-recording shows the countdown and the area selector on top of other " +
                    "apps. Turn on \"Display over other apps\", then come back and tap the button again.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val needed = buildList {
            if (audio.isChecked) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) askToCapture() else requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_PERMISSIONS) askToCapture()
    }

    private fun askToCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE || resultCode != RESULT_OK || data == null) return
        val systemAudio = audio.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        startForegroundService(Intent(this, RecordService::class.java)
            .putExtra(RecordService.EXTRA_CODE, resultCode)
            .putExtra(RecordService.EXTRA_DATA, data)
            .putExtra(RecordService.EXTRA_AREA, pickArea)
            .putExtra(RecordService.EXTRA_LIMIT, limit.isChecked)
            .putExtra(RecordService.EXTRA_AUDIO, systemAudio)
            .putExtra(RecordService.EXTRA_FPS, if (fps60.isChecked) 60 else 30))
        moveTaskToBack(true)
    }

    // ---------- Updates ----------
    private fun checkForUpdates(manual: Boolean) {
        checkButton.isEnabled = false
        thread {
            val result = runCatching { Updater.findUpdate() }
            main.post {
                checkButton.isEnabled = true
                val found = result.getOrNull()
                when {
                    found != null -> {
                        update = found
                        updateButton.text = "Install version ${found.version}"
                        updateButton.visibility = View.VISIBLE
                    }
                    // Stay quiet at start-up: being offline isn't worth a message.
                    manual && result.isFailure ->
                        statusLabel.text = "Couldn't check for updates: ${result.exceptionOrNull()?.message}"
                    manual -> statusLabel.text = "You have the latest version (${BuildConfig.VERSION_NAME})."
                }
            }
        }
    }

    private fun installUpdate() {
        val release = update ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            statusLabel.text = "Allow installing from this app, then tap the update button again."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateButton.isEnabled = false
        thread {
            val apk = File(cacheDir, "update.apk")
            val result = runCatching {
                Updater.download(release, apk) { percent ->
                    main.post { statusLabel.text = "Downloading version ${release.version}… $percent%" }
                }
                Updater.install(this, apk)
            }
            apk.delete()
            main.post {
                result.exceptionOrNull()?.let { updateFailed(it.message) }
            }
        }
    }

    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                @Suppress("DEPRECATION") startActivity(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            PackageInstaller.STATUS_SUCCESS -> statusLabel.text = "Update installed."
            else -> updateFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
        setIntent(Intent())
    }

    private fun updateFailed(message: String?) {
        updateButton.isEnabled = true
        statusLabel.text = "The update failed; your current version still works.\n$message"
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val REQUEST_CAPTURE = 2
    }
}
