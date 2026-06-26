package de.minitraxx.whisperflow.service

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import de.minitraxx.whisperflow.MainActivity
import de.minitraxx.whisperflow.R
import de.minitraxx.whisperflow.api.ClaudeClient
import de.minitraxx.whisperflow.api.StylePrompts
import de.minitraxx.whisperflow.api.WhisperClient
import de.minitraxx.whisperflow.util.CostTracker
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var buttonView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var isRecording = false
    private var isWalkieTalkieMode = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var isDragging = false
    private var recordingStartTime = 0L

    // Edge-Tab state
    private var isEdgeCollapsed = false
    private var collapsedOnLeft = false
    private var buttonSize = 0

    private var mediaRecorder: MediaRecorder? = null
    private var lastRecordingFile: File? = null
    private var capturedPackage = ""

    private var statusView: TextView? = null
    private var statusParams: WindowManager.LayoutParams? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isDragging && !isRecording && !isEdgeCollapsed) {
            isWalkieTalkieMode = true
            startRecording()
        }
    }

    companion object {
        var isRunning = false
            private set

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "whisperflow_service"
        private const val LONG_PRESS_MS = 500L

        const val PREFS_NAME = "whisperflow_prefs"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        const val KEY_STYLE_PROFILE = "style_profile"
        const val PROFILE_WHATSAPP = "whatsapp"
        const val PROFILE_PROFESSIONAL = "professional"
        const val PROFILE_FORMAL = "formal"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingButtonService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        showFloatingButton()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        longPressHandler.removeCallbacks(longPressRunnable)
        timerHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        if (isRecording) stopRecording(transcribe = false)
        runCatching { if (::buttonView.isInitialized) windowManager.removeView(buttonView) }
        runCatching { statusView?.let { windowManager.removeView(it) } }
        statusView = null
        super.onDestroy()
    }

    // ── Screen helpers ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getScreenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.width()
        else {
            val p = Point(); windowManager.defaultDisplay.getSize(p); p.x
        }

    // ── Floating button setup ──────────────────────────────────────────────────

    private fun showFloatingButton() {
        runCatching { if (::buttonView.isInitialized) windowManager.removeView(buttonView) }
        runCatching { statusView?.let { windowManager.removeView(it) }; statusView = null }

        val dp = resources.displayMetrics.density
        buttonSize = (62 * dp).toInt()
        val pad = (13 * dp).toInt()

        buttonView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            elevation = 10f * dp
        }
        applyIdleStyle()

        params = WindowManager.LayoutParams(
            buttonSize, buttonSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = (120 * dp).toInt()
        }

        buttonView.setOnTouchListener(touchListener)
        windowManager.addView(buttonView, params)
        setupStatusView()
    }

    private fun setupStatusView() {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(Color.parseColor("#E6000000"))
            }
            visibility = android.view.View.GONE
        }
        val sp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + (70 * dp).toInt()
            y = params.y + (16 * dp).toInt()
        }
        statusView = tv
        statusParams = sp
        windowManager.addView(tv, sp)
    }

    private fun updateStatusPosition() {
        val dp = resources.displayMetrics.density
        statusParams?.let { sp ->
            sp.x = params.x + (70 * dp).toInt()
            sp.y = params.y + (16 * dp).toInt()
            statusView?.let { runCatching { windowManager.updateViewLayout(it, sp) } }
        }
    }

    // ── Status overlay ─────────────────────────────────────────────────────────

    private fun showStatus(text: String, color: Int = Color.WHITE) {
        Handler(Looper.getMainLooper()).post {
            statusView?.apply {
                setTextColor(color)
                this.text = text
                visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun hideStatus(delayMs: Long = 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            statusView?.visibility = android.view.View.GONE
        }, delayMs)
    }

    // ── Timer ──────────────────────────────────────────────────────────────────

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val s = recordingSeconds
            val time = if (s < 60) "0:${s.toString().padStart(2, '0')}"
                       else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
            showStatus("● $time", Color.parseColor("#FF3B30"))
            recordingSeconds++
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Visual styles ──────────────────────────────────────────────────────────

    private fun applyIdleStyle() {
        buttonView.alpha = 1f
        val stroke = (1.5f * resources.displayMetrics.density).toInt()
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1C1C1E"))
            setStroke(stroke, Color.parseColor("#3A3A3C"))
        }
    }

    private fun applyRecordingStyle() {
        buttonView.alpha = 1f
        val stroke = (1.5f * resources.displayMetrics.density).toInt()
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#C00020"))
            setStroke(stroke, Color.parseColor("#FF2040"))
        }
    }

    private fun applyEdgeTabStyle() {
        buttonView.alpha = 0.80f
        val stroke = (1.5f * resources.displayMetrics.density).toInt()
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1A1A2E"))
            setStroke(stroke, Color.parseColor("#3A3A6E"))
        }
    }

    // ── Edge-Tab logic ─────────────────────────────────────────────────────────

    private fun isNearEdge(): Boolean {
        val sw = getScreenWidth()
        val thresh = (52 * resources.displayMetrics.density).toInt()
        return params.x < thresh || params.x + buttonSize > sw - thresh
    }

    private fun collapseToEdge() {
        val sw = getScreenWidth()
        collapsedOnLeft = params.x < sw / 2
        isEdgeCollapsed = true
        val target = if (collapsedOnLeft) -(buttonSize / 2) else sw - buttonSize / 2
        animateButtonX(params.x, target, 280, DecelerateInterpolator())
        applyEdgeTabStyle()
        hideStatus()
    }

    private fun expandFromEdge() {
        isEdgeCollapsed = false
        val sw = getScreenWidth()
        val pad = (24 * resources.displayMetrics.density).toInt()
        val target = if (collapsedOnLeft) pad else sw - buttonSize - pad
        animateButtonX(params.x, target, 240, OvershootInterpolator(1.3f))
        applyIdleStyle()
    }

    private fun animateButtonX(from: Int, to: Int, durationMs: Long, interp: TimeInterpolator) {
        ValueAnimator.ofInt(from, to).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(buttonView, params) }
                updateStatusPosition()
            }
            start()
        }
    }

    // ── Touch listener ─────────────────────────────────────────────────────────

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                isDragging = false
                if (!isEdgeCollapsed) {
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) {
                    if (!isDragging) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        // Dragging from collapsed = pop back to full immediately
                        if (isEdgeCollapsed) {
                            isEdgeCollapsed = false
                            applyIdleStyle()
                        }
                    }
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    runCatching { windowManager.updateViewLayout(buttonView, params) }
                    updateStatusPosition()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                val elapsed = System.currentTimeMillis() - touchDownTime
                when {
                    // Tap on collapsed tab → expand
                    isEdgeCollapsed && !isDragging -> expandFromEdge()
                    // Drag to screen edge → collapse (only if not recording)
                    isDragging && !isRecording && isNearEdge() -> collapseToEdge()
                    isDragging -> {}
                    isWalkieTalkieMode -> {
                        isWalkieTalkieMode = false
                        stopRecording(transcribe = true)
                    }
                    elapsed < 350 -> toggleRecording()
                }
                true
            }
            else -> false
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private fun toggleRecording() {
        if (isRecording) stopRecording(transcribe = true) else startRecording()
    }

    private fun startRecording() {
        if (isEdgeCollapsed) expandFromEdge()
        if (CostTracker.isExceeded(this)) {
            Toast.makeText(this, "Guthaben aufgebraucht — bitte in der App aufladen", Toast.LENGTH_LONG).show()
            return
        }
        capturedPackage = WhisperAccessibilityService.activePackage
        val file = File(cacheDir, "wf_${System.currentTimeMillis()}.m4a")
        lastRecordingFile = file
        recordingStartTime = System.currentTimeMillis()
        runCatching {
            mediaRecorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            applyRecordingStyle()
            recordingSeconds = 0
            timerHandler.post(timerRunnable)
            pulse()
        }.onFailure {
            isRecording = false
            lastRecordingFile = null
            file.delete()
        }
    }

    private fun stopRecording(transcribe: Boolean) {
        val durationMs = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(0)
        runCatching { mediaRecorder?.apply { stop(); release() } }
        mediaRecorder = null
        isRecording = false
        applyIdleStyle()
        buttonView.animate().cancel()
        buttonView.scaleX = 1f
        buttonView.scaleY = 1f

        val file = lastRecordingFile ?: return
        lastRecordingFile = null
        timerHandler.removeCallbacks(timerRunnable)

        if (!transcribe || durationMs < 300) {
            file.delete()
            hideStatus()
            return
        }

        showStatus("Transkribiere...", Color.parseColor("#8E8E93"))
        CostTracker.recordAudio(durationMs / 1000L, this)
        serviceScope.launch { processAudio(file) }
    }

    // ── Audio processing ──────────────────────────────────────────────────────

    private suspend fun processAudio(file: File) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAiKey = (prefs.getString(KEY_OPENAI_API_KEY, "") ?: "").trim()
        val anthropicKey = (prefs.getString(KEY_ANTHROPIC_API_KEY, "") ?: "").trim()
        val profile = prefs.getString(KEY_STYLE_PROFILE, PROFILE_WHATSAPP) ?: PROFILE_WHATSAPP

        if (openAiKey.isBlank()) {
            showToast("Kein OpenAI API-Key — bitte in der WhisperFlow-App eintragen")
            hideStatus()
            file.delete()
            return
        }

        val transcription = WhisperClient.transcribe(file, openAiKey).getOrElse {
            showToast("Whisper-Fehler: ${it.message?.take(60)}")
            hideStatus()
            file.delete()
            return
        }
        file.delete()

        val activePackage = capturedPackage
        val effectiveProfile = when {
            activePackage.contains("whatsapp") -> PROFILE_WHATSAPP
            activePackage in setOf(
                "com.google.android.gm",
                "com.microsoft.office.outlook",
                "com.samsung.android.email.provider",
                "de.telekom.mail",
                "com.nine.email",
                "com.ionos.email"
            ) -> PROFILE_PROFESSIONAL
            else -> profile
        }

        val finalText = if (anthropicKey.isNotBlank()) {
            val profileLabel = when (effectiveProfile) {
                PROFILE_PROFESSIONAL -> "Professionell"
                PROFILE_FORMAL -> "Formal"
                else -> "WhatsApp"
            }
            showStatus("Korrigiere [$profileLabel]...", Color.parseColor("#8E8E93"))
            val systemPrompt = when (effectiveProfile) {
                PROFILE_PROFESSIONAL -> StylePrompts.PROFESSIONAL
                PROFILE_FORMAL -> StylePrompts.FORMAL
                else -> StylePrompts.WHATSAPP
            }
            CostTracker.recordClaude(this)
            ClaudeClient.correct(transcription, systemPrompt, anthropicKey)
                .getOrDefault(transcription)
        } else {
            transcription
        }

        withContext(Dispatchers.Main) {
            if (WhisperAccessibilityService.isRunning) {
                hideStatus(1200)
                WhisperAccessibilityService.inject(finalText)
            } else {
                showStatus("Schritt 4 aktivieren!", Color.parseColor("#FF3B30"))
                hideStatus(4000)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("whisperflow", finalText))
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private suspend fun showToast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@FloatingButtonService, text, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else MediaRecorder()

    private fun pulse() {
        if (!isRecording) return
        buttonView.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .setDuration(500)
            .withEndAction {
                if (!isRecording) return@withEndAction
                buttonView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(500)
                    .withEndAction { pulse() }
                    .start()
            }.start()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "WhisperFlow", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperFlow")
            .setContentText("Mikrofon-Button ist aktiv")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
