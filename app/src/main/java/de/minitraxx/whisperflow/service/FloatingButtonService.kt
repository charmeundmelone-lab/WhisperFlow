package de.minitraxx.whisperflow.service

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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var buttonView: FrameLayout
    private lateinit var micIconView: ImageView
    private var recLabelView: TextView? = null
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

    private var pulseRingView: View? = null
    private var pulseRingParams: WindowManager.LayoutParams? = null
    private var pulseRingAnimator: ValueAnimator? = null

    private var previewView: View? = null

    private val amplitudeHandler = Handler(Looper.getMainLooper())
    private val amplitudeHistory = ArrayDeque<Float>()

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
        const val KEY_LANGUAGE = "whisper_language"
        const val KEY_PREVIEW_ENABLED = "preview_enabled"
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
        amplitudeHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        if (isRecording) stopRecording(transcribe = false)
        stopPulseRing()
        hidePreviewOverlay()
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

        micIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        buttonView = FrameLayout(this).apply { addView(micIconView) }
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
            setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * dp
                colors = intArrayOf(Color.parseColor("#CC1C1C1E"), Color.parseColor("#E6000000"))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke((1 * dp).toInt(), Color.parseColor("#44FFFFFF"))
            }
            elevation = 8f * dp
            visibility = View.GONE
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
                visibility = View.VISIBLE
            }
        }
    }

    private fun hideStatus(delayMs: Long = 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            statusView?.visibility = View.GONE
        }, delayMs)
    }

    // ── Amplitude + Waveform ───────────────────────────────────────────────────

    private val WAVE_CHARS = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')

    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val amp = (mediaRecorder?.maxAmplitude ?: 0).coerceIn(0, 32767)
            if (amplitudeHistory.size >= 7) amplitudeHistory.removeFirst()
            amplitudeHistory.addLast(amp / 32767f)

            val s = recordingSeconds
            val time = if (s < 60) "0:${s.toString().padStart(2, '0')}"
                       else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
            val wave = amplitudeHistory.joinToString("") { a ->
                WAVE_CHARS[(a * (WAVE_CHARS.size - 1)).toInt().coerceIn(0, WAVE_CHARS.size - 1)].toString()
            }.padEnd(7, '▁')
            recLabelView?.text = "$wave  $time"
            amplitudeHandler.postDelayed(this, 120)
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            recordingSeconds++
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Visual styles ──────────────────────────────────────────────────────────

    private fun applyIdleStyle() {
        val dp = resources.displayMetrics.density
        buttonView.alpha = 1f
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(Color.parseColor("#2C2C2F"), Color.parseColor("#141416"))
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke((2 * dp).toInt(), Color.parseColor("#48484A"))
        }
        buttonView.elevation = 10f * dp
    }

    private fun applyRecordingStyle() {
        val dp = resources.displayMetrics.density
        buttonView.alpha = 1f
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = buttonSize / 2f
            colors = intArrayOf(Color.parseColor("#FF1A40"), Color.parseColor("#8B0000"))
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke((3 * dp).toInt(), Color.parseColor("#FF4060"))
        }
        buttonView.elevation = 18f * dp
    }

    private fun applyEdgeTabStyle() {
        val dp = resources.displayMetrics.density
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(Color.parseColor("#1E1E30"), Color.parseColor("#0D0D1A"))
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke((1.5f * dp).toInt(), Color.parseColor("#3A3A6E"))
        }
        buttonView.elevation = 4f * dp
    }

    // ── Edge-Tab logic ─────────────────────────────────────────────────────────

    private fun isNearEdge(): Boolean {
        val sw = getScreenWidth()
        val edgeZone = sw / 4
        return params.x < edgeZone || params.x + buttonSize > sw - edgeZone
    }

    private fun collapseToEdge() {
        val sw = getScreenWidth()
        collapsedOnLeft = params.x + buttonSize / 2 < sw / 2
        isEdgeCollapsed = true

        val targetX = if (collapsedOnLeft) 0 else sw - buttonSize
        buttonView.pivotX = if (collapsedOnLeft) 0f else buttonSize.toFloat()
        buttonView.pivotY = buttonSize / 2f

        applyEdgeTabStyle()
        hideStatus()

        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(buttonView, params) }
                updateStatusPosition()
            }
            start()
        }

        buttonView.animate()
            .scaleX(0.5f).alpha(0.75f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun expandFromEdge() {
        isEdgeCollapsed = false
        val pad = (20 * resources.displayMetrics.density).toInt()
        val sw = getScreenWidth()
        val targetX = if (collapsedOnLeft) pad else sw - buttonSize - pad

        applyIdleStyle()
        buttonView.pivotX = if (collapsedOnLeft) 0f else buttonSize.toFloat()
        buttonView.pivotY = buttonSize / 2f

        buttonView.animate()
            .scaleX(1f).alpha(1f)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()

        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 240
            interpolator = OvershootInterpolator(1.3f)
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
                if (isEdgeCollapsed) return@OnTouchListener true
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) {
                    if (!isDragging) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
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
                val swipeDy = event.rawY - initialTouchY
                when {
                    isEdgeCollapsed && !isDragging -> expandFromEdge()
                    // Swipe-up to cycle profile: check before isNearEdge so it works
                    // regardless of button position (button starts at x=24 = left edge zone).
                    isDragging && !isRecording && swipeDy < -80 -> cycleProfile()
                    isDragging && !isRecording && isNearEdge() -> collapseToEdge()
                    // Walkie-Talkie: stop recording on finger-up even if user drifted slightly.
                    isDragging && isWalkieTalkieMode -> {
                        isWalkieTalkieMode = false
                        stopRecording(transcribe = true)
                    }
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

            val dp = resources.displayMetrics.density
            val pillWidth = (132 * dp).toInt()

            recLabelView = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 12f
                letterSpacing = 0.05f
                text = "▁▁▁▁▁▁▁  0:00"
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
            }
            micIconView.visibility = View.GONE
            buttonView.addView(recLabelView)

            applyRecordingStyle()
            ValueAnimator.ofInt(buttonSize, pillWidth).apply {
                duration = 220
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener {
                    params.width = it.animatedValue as Int
                    runCatching { windowManager.updateViewLayout(buttonView, params) }
                }
                start()
            }

            amplitudeHistory.clear()
            recordingSeconds = 0
            amplitudeHandler.post(amplitudeRunnable)
            timerHandler.post(timerRunnable)
            startPulseRing()
        }.onFailure {
            isRecording = false
            recLabelView = null
            micIconView.visibility = View.VISIBLE
            lastRecordingFile = null
            file.delete()
        }
    }

    private fun stopRecording(transcribe: Boolean) {
        val durationMs = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(0)
        runCatching { mediaRecorder?.apply { stop(); release() } }
        mediaRecorder = null
        isRecording = false

        buttonView.removeView(recLabelView)
        recLabelView = null
        micIconView.visibility = View.VISIBLE
        applyIdleStyle()
        buttonView.animate().cancel()
        buttonView.scaleX = 1f
        buttonView.scaleY = 1f
        stopPulseRing()
        ValueAnimator.ofInt(params.width, buttonSize).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.width = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(buttonView, params) }
            }
            start()
        }

        val file = lastRecordingFile ?: return
        lastRecordingFile = null
        timerHandler.removeCallbacks(timerRunnable)
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        amplitudeHistory.clear()

        if (!transcribe || durationMs < 300) {
            file.delete()
            hideStatus()
            return
        }

        showStatus("Transkribiere...", Color.parseColor("#8E8E93"))
        CostTracker.recordAudio(durationMs / 1000L, this)
        serviceScope.launch { processAudio(file) }
    }

    // ── Pulse ring ────────────────────────────────────────────────────────────

    private fun startPulseRing() {
        stopPulseRing()
        val dp = resources.displayMetrics.density
        val ringSize = (buttonSize * 2.2f).toInt()

        val ringDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((3 * dp).toInt(), Color.parseColor("#80FF3B30"))
        }
        val ringView = View(this).apply { background = ringDrawable }

        val offset = (buttonSize - ringSize) / 2
        val rp = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + offset
            y = params.y + offset
        }

        pulseRingView = ringView
        pulseRingParams = rp
        windowManager.addView(ringView, rp)

        pulseRingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                ringView.alpha = 1f - f
                val scale = 0.6f + 0.8f * f
                val sz = (buttonSize * scale).toInt().coerceAtLeast(1)
                val off = (buttonSize - sz) / 2
                rp.width = sz
                rp.height = sz
                rp.x = params.x + off
                rp.y = params.y + off
                runCatching { windowManager.updateViewLayout(ringView, rp) }
            }
            start()
        }
    }

    private fun stopPulseRing() {
        pulseRingAnimator?.cancel()
        pulseRingAnimator = null
        runCatching { pulseRingView?.let { windowManager.removeView(it) } }
        pulseRingView = null
        pulseRingParams = null
    }

    // ── Audio processing ──────────────────────────────────────────────────────

    private suspend fun processAudio(file: File) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAiKey = (prefs.getString(KEY_OPENAI_API_KEY, "") ?: "").trim()
        val anthropicKey = (prefs.getString(KEY_ANTHROPIC_API_KEY, "") ?: "").trim()
        val profile = prefs.getString(KEY_STYLE_PROFILE, PROFILE_WHATSAPP) ?: PROFILE_WHATSAPP
        val language = (prefs.getString(KEY_LANGUAGE, "") ?: "").trim()
        val previewEnabled = prefs.getBoolean(KEY_PREVIEW_ENABLED, false)

        if (openAiKey.isBlank()) {
            showToast("Kein OpenAI API-Key — bitte in der WhisperFlow-App eintragen")
            hideStatus()
            file.delete()
            return
        }

        val transcription = WhisperClient.transcribe(file, openAiKey, language).getOrElse {
            showToast("Whisper-Fehler: ${it.message?.take(60)}")
            hideStatus()
            file.delete()
            return
        }.removeFillWords()
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

        val finalText = (if (anthropicKey.isNotBlank()) {
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
        }).stripDictationPrefix().replacePunctuationWords()

        withContext(Dispatchers.Main) {
            if (previewEnabled && WhisperAccessibilityService.isRunning) {
                hideStatus()
                showPreviewOverlay(finalText)
            } else if (WhisperAccessibilityService.isRunning) {
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

    // ── Korrektur-Vorschau ────────────────────────────────────────────────────

    private fun showPreviewOverlay(text: String) {
        hidePreviewOverlay()
        val dp = resources.displayMetrics.density

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                colors = intArrayOf(Color.parseColor("#E01C1C1E"), Color.parseColor("#F0000000"))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke((1 * dp).toInt(), Color.parseColor("#44FFFFFF"))
            }
            elevation = 16f * dp
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        }

        val previewText = TextView(this).apply {
            setText(text.take(240) + if (text.length > 240) "…" else "")
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 6
            setLineSpacing(2 * dp, 1f)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }

        val cancelBtn = Button(this).apply {
            setText("✕  Verwerfen")
            setTextColor(Color.parseColor("#8E8E93"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(Color.parseColor("#2C2C2E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(0, 0, (4 * dp).toInt(), 0) }
            setOnClickListener { hidePreviewOverlay() }
        }

        val confirmBtn = Button(this).apply {
            setText("✓  Einfügen")
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(Color.parseColor("#0A84FF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins((4 * dp).toInt(), 0, 0, 0) }
            setOnClickListener {
                hidePreviewOverlay()
                WhisperAccessibilityService.inject(text)
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(confirmBtn)
        layout.addView(previewText)
        layout.addView(buttonRow)

        val sw = getScreenWidth()
        val width = (sw * 0.85f).toInt()
        val pp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - width) / 2
            y = params.y + buttonSize + (20 * dp).toInt()
        }

        previewView = layout
        windowManager.addView(layout, pp)
        Handler(Looper.getMainLooper()).postDelayed({ hidePreviewOverlay() }, 10_000)
    }

    private fun hidePreviewOverlay() {
        runCatching { previewView?.let { windowManager.removeView(it) } }
        previewView = null
    }

    // ── Text transformations ──────────────────────────────────────────────────

    private fun String.stripDictationPrefix(): String {
        val prefixes = listOf("nachricht", "text", "diktat", "message")
        val trimmed = trimStart()
        val lower = trimmed.lowercase()
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                val remainder = trimmed.drop(prefix.length).dropWhile { !it.isLetter() }
                if (remainder.isNotBlank()) return remainder
            }
        }
        return trimmed
    }

    private fun String.removeFillWords(): String =
        replace(Regex("""\b(ähm|äh|hm|ehm)\b[,\s]*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

    private fun String.replacePunctuationWords(): String =
        replace(Regex("""\bPunkt\b"""), ".")
            .replace(Regex("""\bKomma\b"""), ",")
            .replace(Regex("""\bAusrufezeichen\b"""), "!")
            .replace(Regex("""\bFragezeichen\b"""), "?")
            .replace(Regex("""\bDoppelpunkt\b"""), ":")
            .replace(Regex("""\bSemikolon\b"""), ";")
            .replace(Regex("""\b(Absatz|neue[rn]?\s+Zeile|neuer\s+Absatz)\b""", RegexOption.IGNORE_CASE), "\n")

    // ── Profile cycling (Swipe up) ────────────────────────────────────────────

    private fun cycleProfile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_STYLE_PROFILE, PROFILE_WHATSAPP) ?: PROFILE_WHATSAPP
        val next = when (current) {
            PROFILE_WHATSAPP -> PROFILE_PROFESSIONAL
            PROFILE_PROFESSIONAL -> PROFILE_FORMAL
            else -> PROFILE_WHATSAPP
        }
        prefs.edit().putString(KEY_STYLE_PROFILE, next).apply()
        val label = when (next) {
            PROFILE_PROFESSIONAL -> "Professionell"
            PROFILE_FORMAL -> "Formal"
            else -> "WhatsApp"
        }
        showStatus("↑ Profil: $label", Color.parseColor("#0A84FF"))
        hideStatus(1800)
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
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(600)
            .withEndAction {
                if (!isRecording) return@withEndAction
                buttonView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(600)
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
