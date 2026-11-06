package com.futurehouser.instarecording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs a recording from the moment the capture permission is granted. Everything shown over
 * other apps lives here: start panel, area selector, countdown, stop controls and saving card.
 */
class RecordService : Service() {
    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: Recorder? = null
    private var overlay: View? = null
    private var border: View? = null
    private var output: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    private var pickArea = false
    private var limited = false
    private var withAudio = false
    private var fps = 60
    private var crop = RectF(0f, 0f, 1f, 1f)
    private var startedAt = 0L
    private var recording = false
    private var stopping = false
    private var timerLabel: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (recording) stopRecording(fromNotification = true) else cancel()
            return START_NOT_STICKY
        }
        if (intent == null || recorder != null) return START_NOT_STICKY
        // Android requires the foreground notification before the capture permission is used.
        startForeground(NOTIFICATION_ID, notification("Ready to record", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        pickArea = intent.getBooleanExtra(EXTRA_AREA, false)
        limited = intent.getBooleanExtra(EXTRA_LIMIT, false)
        withAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        fps = intent.getIntExtra(EXTRA_FPS, 60)
        running = true
        status = ""
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)!!
        val capture = manager.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
        capture.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The user ended sharing, or the phone locked.
                if (recording) stopRecording() else if (!stopping) cancel()
            }
        }, main)
        projection = capture
        val size = screenSize()
        recorder = Recorder(capture, size.x, size.y, resources.displayMetrics.densityDpi)
        showStartPanel()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val size = screenSize()
        recorder?.resize(size.x, size.y)
    }

    @Suppress("DEPRECATION")
    private fun screenSize() = Point().also { windows.defaultDisplay.getRealSize(it) }

    // ---------- Overlays ----------
    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or flags,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Positions must be real screen pixels, the same ones the capture uses.
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    /** Overlays the user must be able to touch through: Android only allows that below 80 % opacity. */
    private fun passThroughParams(width: Int, height: Int) = overlayParams(width, height,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    ).apply { alpha = 0.8f }

    private fun show(view: View, params: WindowManager.LayoutParams) {
        hideOverlay()
        windows.addView(view, params)
        overlay = view
    }

    private fun hideOverlay() {
        overlay?.let { windows.removeView(it) }
        overlay = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun card() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(0xEE1E1E1E.toInt())
            cornerRadius = dp(26).toFloat()
        }
    }

    private fun button(text: String, color: Int, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(18).toFloat()
        }
        setPadding(dp(16), 0, dp(16), 0)
        minimumHeight = dp(40)
        minHeight = dp(40)
        setOnClickListener { action() }
    }

    private fun centredTop() = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT, 0).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(90)
    }

    /** Lets the user go to whatever they want to record before anything starts. */
    private fun showStartPanel() {
        val panel = card()
        panel.addView(TextView(this).apply {
            text = "Open what you want to record"
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(12), 0)
        })
        panel.addView(button(if (pickArea) "Select area" else "Start", ACCENT) {
            if (pickArea) showAreaSelector() else showCountdown()
        })
        panel.addView(button("✕", 0xFF444444.toInt()) { cancel() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        show(panel, centredTop())
    }

    private fun showAreaSelector() {
        val size = screenSize()
        val selector = AreaSelector(this) { area ->
            if (area == null) {
                showStartPanel()
            } else {
                crop = area
                showBorder()
                showCountdown()
            }
        }
        show(selector, overlayParams(size.x, size.y, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS))
    }

    /** A red outline just outside the area, so it isn't part of the video. */
    private fun showBorder() {
        val size = screenSize()
        val edge = dp(2)
        val left = (crop.left * size.x).toInt() - edge
        val top = (crop.top * size.y).toInt() - edge
        val view = object : View(this) {
            private val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = edge.toFloat()
                color = ACCENT
            }

            override fun onDraw(canvas: Canvas) {
                val half = edge / 2f
                canvas.drawRect(half, half, width - half, height - half, paint)
            }
        }
        val params = passThroughParams((crop.width() * size.x).toInt() + edge * 2,
            (crop.height() * size.y).toInt() + edge * 2)
        params.x = left
        params.y = top
        windows.addView(view, params)
        border = view
    }

    private fun showCountdown() {
        val diameter = dp(180)
        val label = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 86f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1E1E1E.toInt())
            }
        }
        val size = screenSize()
        show(label, passThroughParams(diameter, diameter).apply {
            x = ((crop.centerX() * size.x).toInt() - diameter / 2).coerceIn(0, size.x - diameter)
            y = ((crop.centerY() * size.y).toInt() - diameter / 2).coerceIn(0, size.y - diameter)
        })
        fun step(n: Int) {
            label.text = n.toString()
            if (n > 0) {
                main.postDelayed({ step(n - 1) }, 1000)
            } else {
                main.postDelayed({
                    hideOverlay()
                    // Give the screen time to redraw without the countdown before recording.
                    main.postDelayed(::startRecording, 150)
                }, 250)
            }
        }
        step(COUNTDOWN_FROM)
    }

    private fun showStopControls() {
        // Only an area leaves room for controls that stay out of the video.
        if (crop.width() >= 1f && crop.height() >= 1f) return
        val pill = card()
        val timer = TextView(this).apply {
            setTextColor(ACCENT)
            setPadding(0, 0, dp(10), 0)
        }
        timerLabel = timer
        pill.addView(timer)
        pill.addView(button("■ Stop", ACCENT) { stopRecording() })
        val size = screenSize()
        pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val below = (crop.bottom * size.y).toInt() + dp(8)
        val above = (crop.top * size.y).toInt() - dp(8) - pill.measuredHeight
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        params.x = ((crop.left * size.x).toInt()).coerceIn(0, maxOf(0, size.x - pill.measuredWidth))
        params.y = when {
            below + pill.measuredHeight <= size.y -> below
            above >= 0 -> above
            else -> (crop.top * size.y).toInt() + dp(8)  // no room outside: it will appear in the video
        }
        show(pill, params)
        tickTimer()
    }

    private fun tickTimer() {
        val label = timerLabel ?: return
        if (!recording) return
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        val text = "● %02d:%02d".format(seconds / 60, seconds % 60)
        label.text = if (limited) "$text / 00:%02d".format(LIMIT_SECONDS) else text
        main.postDelayed(::tickTimer, 500)
    }

    private fun showSaving() {
        val panel = card()
        panel.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)))
        panel.addView(TextView(this).apply {
            text = "Saving video…"
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(4), dp(8))
        })
        show(panel, centredTop())
    }

    // ---------- Recording ----------
    private fun startRecording() {
        val rec = recorder ?: return
        try {
            val name = "Insta-recording_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".mp4"
            val uri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Insta-recording")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                })!!
            outputUri = uri
            output = contentResolver.openFileDescriptor(uri, "rw")
            rec.start(crop, fps, withAudio, LIMIT_SECONDS.takeIf { limited }, output!!.fileDescriptor) { error ->
                main.post { fail("Couldn't start recording: ${error.message}") }
            }
        } catch (e: Exception) {
            Log.e("InstaRecording", "Couldn't create the video file", e)
            fail("Couldn't create the video file: ${e.message}")
            return
        }
        recording = true
        startedAt = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
            notification("Recording", System.currentTimeMillis()))
        showStopControls()
        // The file itself ends at the limit; this only has to come slightly after that.
        if (limited) main.postDelayed({ stopRecording() }, LIMIT_SECONDS * 1000L + 500)
    }

    private fun stopRecording(fromNotification: Boolean = false) {
        if (!recording) return
        recording = false
        stopping = true
        main.removeCallbacksAndMessages(null)
        removeBorder()
        showSaving()
        // In a full-screen video the end shows the notification shade being pulled down.
        val shadeVisible = fromNotification && crop.width() >= 1f && crop.height() >= 1f
        recorder?.stop(shadeVisible) { ok -> main.post { finished(ok) } }
    }

    private fun finished(ok: Boolean) {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).apply {
            startTone(ToneGenerator.TONE_PROP_ACK, 300)
            main.postDelayed({ release() }, 600)
        }
        val uri = outputUri
        runCatching { output?.close() }
        output = null
        if (uri != null) {
            if (ok) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                savedVideo = uri
                status = "Saved to Movies/Insta-recording"
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
                status = "Recording failed: no video was captured."
            }
        }
        cleanUp()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun fail(message: String) {
        recording = false
        outputUri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
        runCatching { output?.close() }
        output = null
        status = message
        cleanUp()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun cancel() {
        cleanUp()
    }

    private fun removeBorder() {
        border?.let { windows.removeView(it) }
        border = null
    }

    private fun cleanUp() {
        main.removeCallbacksAndMessages(null)
        timerLabel = null
        hideOverlay()
        removeBorder()
        recorder?.release()
        recorder = null
        projection?.stop()
        projection = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String, chronometerFrom: Long?): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Insta-recording")
            .setContentText(text)
            .setOngoing(true)
            .apply {
                if (chronometerFrom != null) {
                    setWhen(chronometerFrom)
                    setUsesChronometer(true)
                    setShowWhen(true)
                }
            }
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    @SuppressLint("ViewConstructor")
    private class AreaSelector(context: Context, val onDone: (RectF?) -> Unit) : View(context) {
        private val dim = Paint().apply { color = 0x88000000.toInt() }
        private val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = ACCENT
        }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15 * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }
        private var startX = 0f
        private var startY = 0f
        private var box: RectF? = null

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = box
            if (r == null) {
                canvas.drawRect(0f, 0f, w, h, dim)
                canvas.drawText("Drag over the area to record", w / 2, h / 2, hint)
                canvas.drawText("Tap to go back", w / 2, h / 2 + hint.textSize * 1.6f, hint)
            } else {
                canvas.drawRect(0f, 0f, w, r.top, dim)
                canvas.drawRect(0f, r.bottom, w, h, dim)
                canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
                canvas.drawRect(r.right, r.top, w, r.bottom, dim)
                canvas.drawRect(r, line)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    box = null
                }
                MotionEvent.ACTION_MOVE -> {
                    box = RectF(minOf(startX, event.x), minOf(startY, event.y),
                        maxOf(startX, event.x), maxOf(startY, event.y))
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val r = box
                    val minimum = 48 * resources.displayMetrics.density
                    if (r == null || r.width() < minimum || r.height() < minimum) {
                        onDone(null)
                    } else {
                        onDone(RectF(r.left / width, r.top / height, r.right / width, r.bottom / height))
                    }
                }
            }
            return true
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AREA = "area"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_FPS = "fps"
        private const val ACTION_STOP = "com.futurehouser.instarecording.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val COUNTDOWN_FROM = 3
        private const val LIMIT_SECONDS = 59
        private const val ACCENT = 0xFFFF3B5C.toInt()

        @Volatile var running = false
        @Volatile var status = ""
        @Volatile var savedVideo: Uri? = null
    }
}
