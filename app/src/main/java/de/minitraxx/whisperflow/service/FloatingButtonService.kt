package de.minitraxx.whisperflow.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
import de.minitraxx.whisperflow.whisper.LocalWhisperEngine
import de.minitraxx.whisperflow.whisper.ModelManager
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var buttonView: FrameLayout
    private lateinit var micIconView: ImageView
    private var recLabelView: TextView? = null
    private lateinit var params: WindowManager.LayoutParams

    private var isRecording = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var isDragging = false
    private var menuClosedByDown = false
    private var recordingStartTime = 0L

    private var isEdgeCollapsed = false
    private var collapsedOnLeft = false
    private var preCollapseY = 0
    private var buttonSize = 0

    private var mediaRecorder: MediaRecorder? = null
    private var lastRecordingFile: File? = null
    private var capturedPackage = ""
    private var currentMaxSeconds = 30

    private var durationBadgeView: TextView? = null
    private var durationBadgeParams: WindowManager.LayoutParams? = null
    private var miniBadgeView: TextView? = null
    private var miniBadgeParams: WindowManager.LayoutParams? = null
    private var emojiBadgeView: TextView? = null
    private var emojiBadgeParams: WindowManager.LayoutParams? = null

    private var statusView: TextView? = null
    private var statusParams: WindowManager.LayoutParams? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0

    private var pulseRingView: View? = null
    private var pulseRingParams: WindowManager.LayoutParams? = null
    private var pulseRingAnimator: ValueAnimator? = null

    private var previewView: View? = null
    private var previewBottomSheet: View? = null
    private var previewSheetParams: WindowManager.LayoutParams? = null
    private var previewSentences = mutableListOf<String>()
    private var selectedSentenceIndex = -1
    private var sentenceViews = mutableListOf<TextView>()
    private var editingSentenceIndex = -1
    private var editingEditText: EditText? = null
    private var editingEditBtn: TextView? = null
    private var isMiniRecording = false
    private var miniRecordingFile: File? = null
    private var miniRecordingStartTime = 0L
    private var miniMediaRecorder: MediaRecorder? = null
    private var miniRecordingTargetIdx = -1
    private var miniAutoStopRunnable: Runnable? = null
    private val miniTimerHandler = Handler(Looper.getMainLooper())
    private var miniTimerRunnable: Runnable? = null
    private var actionRow: LinearLayout? = null
    private val undoStack = ArrayDeque<Pair<Int, String>>() // index + text
    private var undoBtn: TextView? = null

    private val amplitudeHandler = Handler(Looper.getMainLooper())
    private val amplitudeHistory = ArrayDeque<Float>()

    private val boomHandler = Handler(Looper.getMainLooper())
    private var boomView: View? = null
    private var isBoomPending = false
    private var discardGestureActive = false

    private var isOnRightEdge = true
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val inactivityRunnable = Runnable {
        if (!isRecording && !menuActive && !isEdgeCollapsed) collapseToEdge()
    }

    // ── Radial menu state ──────────────────────────────────────────────────────
    private var menuActive = false
    private val menuViews = mutableListOf<View>()
    private val menuValueViews = mutableListOf<TextView>()
    private val menuCloseHandler = Handler(Looper.getMainLooper())
    private val menuCloseRunnable = Runnable { if (menuActive) closeRadialMenu() }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isDragging && !isRecording && !isEdgeCollapsed) {
            openRadialMenu()
        }
    }

    companion object {
        var isRunning = false
            private set

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "whisperflow_service"
        private const val LONG_PRESS_MS = 500L
        private const val MAX_RECORDING_SECONDS = 90
        private const val BOOM_WARNING_SECS = 10
        const val KEY_MAX_DURATION = "max_duration"
        private const val DURATION_MINI = 10
        private val DURATION_PRESETS = listOf(30, 90, 180, 300)
        // On-Device Whisper (Option 1): nur Aufnahmen bis 30s (ein Whisper-Fenster).
        // Kleiner Puffer, weil durationMs minimal über der Preset-Grenze liegen kann.
        private const val LOCAL_WHISPER_MAX_MS = 31_500L
        private const val INACTIVITY_TIMEOUT_MS = 5000L
        const val KEY_EDGE_SIDE = "edge_side"

        const val PREFS_NAME = "whisperflow_prefs"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        const val KEY_STYLE_PROFILE = "style_profile"
        const val KEY_LANGUAGE = "whisper_language"
        const val KEY_PREVIEW_ENABLED = "preview_enabled"
        const val KEY_ONDEVICE_WHISPER = "ondevice_whisper"
        const val KEY_ONDEVICE_LAST_DIAG = "ondevice_last_diag"
        const val KEY_EMOJI_LEVEL = "emoji_level"
        const val KEY_EMOJI_ENABLED = "emoji_enabled"
        const val KEY_HEADINGS_ENABLED = "headings_enabled"
        const val PROFILE_WHATSAPP = "whatsapp"
        const val PROFILE_PROFESSIONAL = "professional"
        const val PROFILE_FORMAL = "formal"
        const val EMOJI_NONE = "none"
        const val EMOJI_FEW = "few"
        const val EMOJI_MANY = "many"

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
        cancelInactivityTimer()
        longPressHandler.removeCallbacks(longPressRunnable)
        menuCloseHandler.removeCallbacksAndMessages(null)
        timerHandler.removeCallbacksAndMessages(null)
        amplitudeHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        if (isRecording) stopRecording(transcribe = false)
        if (menuActive) {
            menuViews.forEach { runCatching { windowManager.removeView(it) } }
            menuViews.clear()
            menuValueViews.clear()
            menuActive = false
        }
        stopPulseRing()
        hidePreviewOverlay()
        removeBadges()
        boomHandler.removeCallbacksAndMessages(null)
        runCatching { boomView?.let { windowManager.removeView(it) } }
        boomView = null
        runCatching { if (::buttonView.isInitialized) windowManager.removeView(buttonView) }
        runCatching { statusView?.let { windowManager.removeView(it) } }
        statusView = null
        LocalWhisperEngine.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val sw = getScreenWidth()
        val sh = getScreenHeight()
        if (isEdgeCollapsed) {
            val dp = resources.displayMetrics.density
            val stripW = (16 * dp).toInt()
            val stripH = (64 * dp).toInt()
            params.x = if (collapsedOnLeft) 0 else sw - stripW
            params.y = params.y.coerceIn(0, (sh - stripH).coerceAtLeast(0))
            params.width = stripW
            params.height = stripH
        } else {
            params.x = if (isOnRightEdge) sw - buttonSize else 0
            params.y = params.y.coerceIn(0, (sh - buttonSize).coerceAtLeast(0))
            params.width = buttonSize
            params.height = buttonSize
        }
        runCatching { windowManager.updateViewLayout(buttonView, params) }
        updateStatusPosition()
        updateBadgePositions()
    }

    // ── Screen helpers ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getScreenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.width()
        else {
            val p = Point(); windowManager.defaultDisplay.getSize(p); p.x
        }

    @Suppress("DEPRECATION")
    private fun getScreenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.height()
        else {
            val p = Point(); windowManager.defaultDisplay.getSize(p); p.y
        }

    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()

    // ── Floating button setup ──────────────────────────────────────────────────

    private fun showFloatingButton() {
        removeBadges()
        runCatching { if (::buttonView.isInitialized) windowManager.removeView(buttonView) }
        runCatching { statusView?.let { windowManager.removeView(it) }; statusView = null }

        val dp = resources.displayMetrics.density
        buttonSize = (62 * dp).toInt()
        val pad = (13 * dp).toInt()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isOnRightEdge = prefs.getBoolean(KEY_EDGE_SIDE, true)

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

        val sw = getScreenWidth()
        params = WindowManager.LayoutParams(
            buttonSize, buttonSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (isOnRightEdge) sw - buttonSize else 0
            y = (120 * dp).toInt()
        }

        buttonView.setOnTouchListener(touchListener)
        windowManager.addView(buttonView, params)
        setupStatusView()
        setupDurationBadges()
        resetInactivityTimer()
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
            x = statusX(dp)
            y = params.y + (16 * dp).toInt()
        }
        statusView = tv
        statusParams = sp
        windowManager.addView(tv, sp)
    }

    private fun statusX(dp: Float): Int =
        if (isOnRightEdge) (params.x - (185 * dp).toInt()).coerceAtLeast(4)
        else params.x + buttonSize + (8 * dp).toInt()

    private fun updateStatusPosition() {
        val dp = resources.displayMetrics.density
        statusParams?.let { sp ->
            sp.x = statusX(dp)
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
            val secsLeft = currentMaxSeconds - recordingSeconds
            if (secsLeft <= BOOM_WARNING_SECS && currentMaxSeconds > BOOM_WARNING_SECS) {
                val blink = (System.currentTimeMillis() / 400) % 2 == 0L
                recLabelView?.setTextColor(if (blink) Color.parseColor("#FF3B30") else Color.parseColor("#FF9500"))
            } else {
                recLabelView?.setTextColor(Color.WHITE)
            }
            amplitudeHandler.postDelayed(this, 120)
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            recordingSeconds++
            if (recordingSeconds >= currentMaxSeconds) {
                triggerBoomStop()
                return
            }
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

    private fun applyEdgeTabStyle(onLeft: Boolean) {
        val dp = resources.displayMetrics.density
        val r = 10f * dp
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // cornerRadii order: [TL, TL, TR, TR, BR, BR, BL, BL]
            cornerRadii = if (onLeft) {
                // Left edge: flat left side, rounded right side
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            } else {
                // Right edge: rounded left side, flat right side
                floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            colors = intArrayOf(Color.parseColor("#FFD60A"), Color.parseColor("#F5A800"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        buttonView.elevation = 8f * dp
        buttonView.alpha = 0.92f
    }

    // ── Edge-Tab logic ─────────────────────────────────────────────────────────

    private fun collapseToEdge() {
        cancelInactivityTimer()
        val dp = resources.displayMetrics.density
        val sw = getScreenWidth()
        collapsedOnLeft = !isOnRightEdge
        isEdgeCollapsed = true
        preCollapseY = params.y

        val stripW = (16 * dp).toInt()
        val stripH = (64 * dp).toInt()
        val targetX = if (collapsedOnLeft) 0 else sw - stripW
        val targetY = params.y + (buttonSize - stripH) / 2

        val startX = params.x
        val startY = params.y
        val startW = params.width
        val startH = params.height

        micIconView.animate().alpha(0f).setDuration(140).start()
        applyEdgeTabStyle(collapsedOnLeft)
        hideStatus()
        hideBadges()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                params.x = lerp(startX, targetX, t)
                params.y = lerp(startY, targetY, t)
                params.width = lerp(startW, stripW, t)
                params.height = lerp(startH, stripH, t)
                runCatching { windowManager.updateViewLayout(buttonView, params) }
                updateStatusPosition()
            }
            start()
        }
    }

    private fun expandFromEdge() {
        isEdgeCollapsed = false
        val sw = getScreenWidth()

        val targetX = if (collapsedOnLeft) 0 else sw - buttonSize
        val targetY = preCollapseY

        val startX = params.x
        val startY = params.y
        val startW = params.width
        val startH = params.height

        isOnRightEdge = !collapsedOnLeft
        applyIdleStyle()
        micIconView.animate().alpha(1f).setDuration(200).setStartDelay(160).start()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.3f)
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                params.x = lerp(startX, targetX, t)
                params.y = lerp(startY, targetY, t)
                params.width = lerp(startW, buttonSize, t)
                params.height = lerp(startH, buttonSize, t)
                runCatching { windowManager.updateViewLayout(buttonView, params) }
                updateStatusPosition()
                updateBadgePositions()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    showBadges()
                    resetInactivityTimer()
                }
            })
            start()
        }
    }

    private fun snapToNearestEdge() {
        val sw = getScreenWidth()
        isOnRightEdge = params.x + buttonSize / 2 > sw / 2
        saveEdgeSide()
        val targetX = if (isOnRightEdge) sw - buttonSize else 0
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(buttonView, params) }
                updateStatusPosition()
                updateBadgePositions()
            }
            start()
        }
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (!isEdgeCollapsed && !isRecording) {
            inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
        }
    }

    private fun cancelInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
    }

    private fun saveEdgeSide() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EDGE_SIDE, isOnRightEdge).apply()
    }

    // ── Radial menu ────────────────────────────────────────────────────────────

    private fun openRadialMenu() {
        if (menuActive || isRecording) return
        cancelInactivityTimer()
        menuActive = true
        menuViews.clear()
        menuValueViews.clear()

        val dp = resources.displayMetrics.density
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentProfile = prefs.getString(KEY_STYLE_PROFILE, PROFILE_WHATSAPP) ?: PROFILE_WHATSAPP
        val currentEmoji = prefs.getString(KEY_EMOJI_LEVEL, EMOJI_FEW) ?: EMOJI_FEW
        val currentLang = prefs.getString(KEY_LANGUAGE, "") ?: ""
        val headingsEnabled = prefs.getBoolean(KEY_HEADINGS_ENABLED, true)

        val sw = getScreenWidth()
        val onRightHalf = params.x + buttonSize / 2 > sw / 2
        // Fan opens toward screen center
        val baseAngleDeg = if (onRightHalf) 180.0 else 0.0
        val radiusPx = 90f * dp

        // Top-to-bottom arc: Profil, Emojis, Sprache, Labels — evenly spaced 50° apart.
        // Offsets are flipped by side so the visual order stays consistent (Y is inverted in Android).
        val angleOffsets = if (onRightHalf) listOf(-75.0, -25.0, 25.0, 75.0) else listOf(75.0, 25.0, -25.0, -75.0)
        val containerWidth = (72 * dp).toInt()
        val circleSize = (52 * dp).toInt()
        val btnCX = params.x + buttonSize / 2
        val btnCY = params.y + buttonSize / 2

        val items = listOf(
            Triple("Profil",   profileDisplayValue(currentProfile),     0),
            Triple("Emojis",   emojiDisplayValue(currentEmoji),         1),
            Triple("Sprache",  langDisplayValue(currentLang),           2),
            Triple("Labels",   headingsDisplayValue(headingsEnabled),   3)
        )

        items.forEachIndexed { idx, (label, value, _) ->
            val angleRad = Math.toRadians(baseAngleDeg + angleOffsets[idx])
            val itemCX = (btnCX + radiusPx * cos(angleRad)).toInt()
            val itemCY = (btnCY - radiusPx * sin(angleRad)).toInt()  // Y inverted in Android

            val (container, valueTv) = createMenuItemView(label, value, circleSize, containerWidth, dp)
            menuViews.add(container)
            menuValueViews.add(valueTv)

            val capturedIdx = idx
            container.setOnClickListener {
                onMenuItemTap(capturedIdx)
                scheduleMenuClose()
            }

            val lp = WindowManager.LayoutParams(
                containerWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (itemCX - containerWidth / 2).coerceIn(0, sw - containerWidth)
                y = (itemCY - circleSize / 2 - (12 * dp).toInt()).coerceAtLeast(0)
            }

            container.scaleX = 0f
            container.scaleY = 0f
            container.alpha = 0f
            windowManager.addView(container, lp)

            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220)
                .setStartDelay((idx * 55).toLong())
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()
        }

        scheduleMenuClose()
    }

    private fun createMenuItemView(
        label: String,
        value: String,
        circleSize: Int,
        containerWidth: Int,
        dp: Float
    ): Pair<LinearLayout, TextView> {
        val valueTv = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val circle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6111116"))
                setStroke((2 * dp).toInt(), Color.parseColor("#55FFD60A"))
            }
            elevation = 6f * dp
            addView(valueTv)
        }

        val caption = TextView(this).apply {
            text = label
            textSize = 9f
            setTextColor(Color.parseColor("#99FFFFFF"))
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (5 * dp).toInt()
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(circle)
            addView(caption)
        }

        return Pair(container, valueTv)
    }

    private fun onMenuItemTap(index: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Handler(Looper.getMainLooper()).post {
            when (index) {
                0 -> {
                    val next = when (prefs.getString(KEY_STYLE_PROFILE, PROFILE_WHATSAPP) ?: PROFILE_WHATSAPP) {
                        PROFILE_WHATSAPP    -> PROFILE_PROFESSIONAL
                        PROFILE_PROFESSIONAL -> PROFILE_FORMAL
                        else                -> PROFILE_WHATSAPP
                    }
                    prefs.edit().putString(KEY_STYLE_PROFILE, next).apply()
                    menuValueViews.getOrNull(0)?.text = profileDisplayValue(next)
                }
                1 -> {
                    val next = when (prefs.getString(KEY_EMOJI_LEVEL, EMOJI_FEW) ?: EMOJI_FEW) {
                        EMOJI_NONE -> EMOJI_FEW
                        EMOJI_FEW  -> EMOJI_MANY
                        else       -> EMOJI_NONE
                    }
                    prefs.edit().putString(KEY_EMOJI_LEVEL, next).apply()
                    menuValueViews.getOrNull(1)?.text = emojiDisplayValue(next)
                }
                2 -> {
                    val next = when (prefs.getString(KEY_LANGUAGE, "") ?: "") {
                        ""      -> "de"
                        "de"    -> "en"
                        "en"    -> "platt"
                        else    -> ""
                    }
                    prefs.edit().putString(KEY_LANGUAGE, next).apply()
                    menuValueViews.getOrNull(2)?.text = langDisplayValue(next)
                }
                3 -> {
                    val next = !prefs.getBoolean(KEY_HEADINGS_ENABLED, true)
                    prefs.edit().putBoolean(KEY_HEADINGS_ENABLED, next).apply()
                    menuValueViews.getOrNull(3)?.text = headingsDisplayValue(next)
                }
            }
        }
    }

    private fun closeRadialMenu() {
        menuCloseHandler.removeCallbacks(menuCloseRunnable)
        menuActive = false
        resetInactivityTimer()
        val views = menuViews.toList()
        menuViews.clear()
        menuValueViews.clear()

        views.forEachIndexed { idx, view ->
            view.animate()
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setDuration(160)
                .setStartDelay((idx * 25).toLong())
                .withEndAction { runCatching { windowManager.removeView(view) } }
                .start()
        }
    }

    private fun scheduleMenuClose() {
        menuCloseHandler.removeCallbacks(menuCloseRunnable)
        menuCloseHandler.postDelayed(menuCloseRunnable, 5000L)
    }

    // ── Display value helpers ──────────────────────────────────────────────────

    private fun profileDisplayValue(profile: String) = when (profile) {
        PROFILE_PROFESSIONAL -> "PRO"
        PROFILE_FORMAL       -> "FOR"
        else                 -> "WA"
    }

    private fun emojiDisplayValue(level: String) = when (level) {
        EMOJI_NONE -> "—"
        EMOJI_MANY -> "🎉"
        else       -> "🙂"
    }

    private fun langDisplayValue(lang: String) = when (lang) {
        "de"    -> "DE"
        "en"    -> "EN"
        "platt" -> "PLT"
        else    -> "🌐"
    }

    private fun headingsDisplayValue(enabled: Boolean) = if (enabled) "AN" else "AUS"

    // ── Touch listener ─────────────────────────────────────────────────────────

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (menuActive) {
                    closeRadialMenu()
                    menuClosedByDown = true
                    return@OnTouchListener true
                }
                menuClosedByDown = false
                cancelInactivityTimer()
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                isDragging = false
                discardGestureActive = false
                if (!isEdgeCollapsed) {
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isEdgeCollapsed || menuClosedByDown) return@OnTouchListener true
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) {
                    if (!isDragging) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    if (!isRecording) {
                        params.x = (initialX + dx).coerceAtLeast(0)
                        params.y = (initialY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(buttonView, params) }
                        updateStatusPosition()
                        updateBadgePositions()
                    }
                }
                // Wisch nach unten während Aufnahme = Verwerfen-Vorschau
                if (isRecording) {
                    val dp = resources.displayMetrics.density
                    val threshold = 80 * dp
                    if (dy > threshold && dy > abs(dx)) {
                        if (!discardGestureActive) {
                            discardGestureActive = true
                            // Visuelles Feedback: Label wird grau
                            Handler(Looper.getMainLooper()).post {
                                recLabelView?.setTextColor(Color.parseColor("#8E8E93"))
                                showStatus("↓ Loslassen zum Verwerfen", Color.parseColor("#FF453A"))
                            }
                        }
                    } else if (discardGestureActive) {
                        discardGestureActive = false
                        Handler(Looper.getMainLooper()).post {
                            recLabelView?.setTextColor(Color.WHITE)
                            showStatus("${amplitudeHistory.joinToString("") { a -> WAVE_CHARS[(a * (WAVE_CHARS.size - 1)).toInt().coerceIn(0, WAVE_CHARS.size - 1)].toString() }}  ...", Color.WHITE)
                        }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (menuClosedByDown) {
                    menuClosedByDown = false
                    return@OnTouchListener true
                }
                if (isRecording && discardGestureActive) {
                    discardRecording()
                    return@OnTouchListener true
                }
                val elapsed = System.currentTimeMillis() - touchDownTime
                when {
                    isEdgeCollapsed && !isDragging -> expandFromEdge()
                    isDragging                     -> snapToNearestEdge()
                    elapsed < LONG_PRESS_MS        -> toggleRecording()
                    // elapsed >= LONG_PRESS_MS: menu was opened by long press runnable — no-op
                }
                true
            }
            else -> false
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private fun toggleRecording() {
        if (isBoomPending) return
        if (isRecording) stopRecording(transcribe = true) else startRecording()
    }

    private fun discardRecording() {
        if (!isRecording) return
        isRecording = false
        discardGestureActive = false
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        timerHandler.removeCallbacksAndMessages(null)
        boomHandler.removeCallbacksAndMessages(null)
        isBoomPending = false
        recordingSeconds = 0
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        lastRecordingFile?.delete()
        lastRecordingFile = null
        stopPulseRing()
        buttonView.removeView(recLabelView)
        recLabelView = null
        micIconView.alpha = 1f
        micIconView.visibility = View.VISIBLE
        applyIdleStyle()
        buttonView.animate().cancel()
        buttonView.scaleX = 1f
        buttonView.scaleY = 1f
        val swDiscard = getScreenWidth()
        ValueAnimator.ofInt(params.width, buttonSize).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val w = it.animatedValue as Int
                params.width = w
                if (isOnRightEdge) params.x = swDiscard - w
                runCatching { windowManager.updateViewLayout(buttonView, params) }
            }
            start()
        }
        hideStatus()
        showBadges()
        resetInactivityTimer()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@FloatingButtonService, "Aufnahme verworfen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        if (isEdgeCollapsed) expandFromEdge()
        cancelInactivityTimer()
        hideBadges()
        if (CostTracker.isExceeded(this)) {
            // On-Device-Aufnahmen (≤30s-Preset, Modell vorhanden) sind kostenlos —
            // die dürfen auch bei aufgebrauchtem Cloud-Guthaben weiterlaufen.
            val localFree = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONDEVICE_WHISPER, false) &&
                currentMaxSeconds <= 30 &&
                ModelManager.isModelAvailable(this)
            if (!localFree) {
                Toast.makeText(this, "Guthaben aufgebraucht — bitte in der App aufladen", Toast.LENGTH_LONG).show()
                return
            }
        }
        capturedPackage = WhisperAccessibilityService.activePackage
        val file = File(cacheDir, "wf_${System.currentTimeMillis()}.m4a")
        lastRecordingFile = file
        recordingStartTime = System.currentTimeMillis()
        runCatching {
            mediaRecorder = createMediaRecorder().apply {
                // VOICE_RECOGNITION statt MIC: aktiviert die geräteeigene Rauschunterdrückung
                // + automatische Pegelanpassung, getunt für Spracherkennung — entscheidend
                // bei Auto-/Straßenlärm. Rohes MIC lieferte dort massive Verhörer.
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
            micIconView.animate().cancel()
            micIconView.alpha = 1f
            micIconView.visibility = View.GONE
            buttonView.addView(recLabelView)

            applyRecordingStyle()
            val sw = getScreenWidth()
            ValueAnimator.ofInt(buttonSize, pillWidth).apply {
                duration = 220
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener {
                    val w = it.animatedValue as Int
                    params.width = w
                    if (isOnRightEdge) params.x = sw - w
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
        micIconView.alpha = 1f
        micIconView.visibility = View.VISIBLE
        applyIdleStyle()
        buttonView.animate().cancel()
        buttonView.scaleX = 1f
        buttonView.scaleY = 1f
        stopPulseRing()
        val swStop = getScreenWidth()
        ValueAnimator.ofInt(params.width, buttonSize).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val w = it.animatedValue as Int
                params.width = w
                if (isOnRightEdge) params.x = swStop - w
                runCatching { windowManager.updateViewLayout(buttonView, params) }
            }
            start()
        }

        resetInactivityTimer()
        showBadges()
        val file = lastRecordingFile ?: return
        lastRecordingFile = null
        timerHandler.removeCallbacks(timerRunnable)
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        amplitudeHistory.clear()

        if (!transcribe || durationMs < 1500) {
            file.delete()
            hideStatus()
            return
        }

        showStatus("Transkribiere...", Color.parseColor("#8E8E93"))
        serviceScope.launch { processAudio(file, durationMs) }
    }

    // ── BOOM! auto-stop at 90 seconds ─────────────────────────────────────────

    private fun triggerBoomStop() {
        if (!isRecording || isBoomPending) return
        val durationMs = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(0)
        runCatching { mediaRecorder?.apply { stop(); release() } }
        mediaRecorder = null
        isRecording = false
        isBoomPending = true
        timerHandler.removeCallbacks(timerRunnable)
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        amplitudeHistory.clear()

        val file = lastRecordingFile
        lastRecordingFile = null

        hideStatus()
        showBoomOverlay()

        boomHandler.postDelayed({
            isBoomPending = false
            hideBoomOverlay()

            buttonView.removeView(recLabelView)
            recLabelView = null
            micIconView.alpha = 1f
            micIconView.visibility = View.VISIBLE
            applyIdleStyle()
            buttonView.animate().cancel()
            buttonView.scaleX = 1f
            buttonView.scaleY = 1f
            stopPulseRing()
            val swBoom = getScreenWidth()
            ValueAnimator.ofInt(params.width, buttonSize).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val w = it.animatedValue as Int
                    params.width = w
                    if (isOnRightEdge) params.x = swBoom - w
                    runCatching { windowManager.updateViewLayout(buttonView, params) }
                }
                start()
            }

            showBadges()
            resetInactivityTimer()
            if (file == null || durationMs < 300) {
                file?.delete()
                hideStatus()
                return@postDelayed
            }

            showStatus("Transkribiere...", Color.parseColor("#8E8E93"))
            serviceScope.launch { processAudio(file, durationMs) }
        }, 1500)
    }

    private fun showBoomOverlay() {
        hideBoomOverlay()
        val dp = resources.displayMetrics.density
        val size = (180 * dp).toInt()

        val bv = object : View(this@FloatingButtonService) {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#FFD60A")
            }
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * dp
                color = Color.parseColor("#CC7A00")
            }
            private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                strokeJoin = Paint.Join.ROUND
                color = Color.parseColor("#660000")
            }
            private val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                color = Color.parseColor("#FF2200")
            }
            private val burstPath = Path()

            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val outerR = width / 2f * 0.90f
                val innerR = width / 2f * 0.58f
                val points = 16

                burstPath.reset()
                for (i in 0 until points * 2) {
                    val angle = (Math.PI * i / points - Math.PI / 2).toFloat()
                    val r = if (i % 2 == 0) outerR else innerR
                    val x = cx + r * cos(angle)
                    val y = cy + r * sin(angle)
                    if (i == 0) burstPath.moveTo(x, y) else burstPath.lineTo(x, y)
                }
                burstPath.close()

                canvas.drawPath(burstPath, fillPaint)
                canvas.drawPath(burstPath, strokePaint)

                val textSize = width * 0.28f
                textOutlinePaint.textSize = textSize
                textOutlinePaint.strokeWidth = 5f * dp
                textFillPaint.textSize = textSize

                val textY = cy - (textFillPaint.ascent() + textFillPaint.descent()) / 2f
                canvas.drawText("BOOM!", cx, textY, textOutlinePaint)
                canvas.drawText("BOOM!", cx, textY, textFillPaint)
            }
        }

        val sw = getScreenWidth()
        val bx = (params.x + params.width / 2 - size / 2).coerceIn(0, sw - size)
        val by = (params.y + buttonSize / 2 - size / 2).coerceAtLeast(4)

        val bp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bx
            y = by
        }

        boomView = bv
        windowManager.addView(bv, bp)

        bv.scaleX = 0.2f
        bv.scaleY = 0.2f
        bv.alpha = 0f
        bv.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    private fun hideBoomOverlay() {
        val v = boomView ?: return
        boomView = null
        v.animate().cancel()
        v.animate()
            .scaleX(0.1f).scaleY(0.1f).alpha(0f)
            .setDuration(200)
            .withEndAction { runCatching { windowManager.removeView(v) } }
            .start()
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

    /**
     * Transkription mit On-Device-Pfad (Option 1):
     * Feature-Flag AN + Aufnahme ≤30s + Modell vorhanden → lokal (0 €).
     * Jeder On-Device-Fehler fällt still auf Cloud-Whisper zurück (CLAUDE.md-Regel).
     * Kosten werden nur für den tatsächlich genutzten Cloud-Pfad erfasst.
     */
    /**
     * Diagnose der letzten Transkription — sichtbar in der On-Device-Settings-Card.
     * Der Fallback bleibt für den Nutzer im Diktier-Flow still; hier steht trotzdem
     * nachvollziehbar, welcher Pfad lief und warum.
     */
    private fun recordLocalDiag(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ONDEVICE_LAST_DIAG, "$time · $message").apply()
    }

    private suspend fun smartTranscribe(
        file: File,
        whisperLanguage: String,
        durationMs: Long,
        openAiKey: String,
        trackCost: Boolean
    ): Result<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flagOn = prefs.getBoolean(KEY_ONDEVICE_WHISPER, false)
        val durationOk = durationMs in 1..LOCAL_WHISPER_MAX_MS
        val modelOk = ModelManager.isModelAvailable(this)

        if (flagOn && durationOk && modelOk) {
            showStatus("Transkribiere (lokal)...", Color.parseColor("#8E8E93"))
            val startedAt = System.currentTimeMillis()
            val local = LocalWhisperEngine.transcribe(this, file, whisperLanguage)
            local.getOrNull()?.let {
                val secs = (System.currentTimeMillis() - startedAt) / 1000.0
                val modelId = ModelManager.selectedModel(this).id
                recordLocalDiag("Lokal ✓ ($modelId) — ${durationMs / 1000}s Audio in ${"%.1f".format(secs)}s (0 €)")
                return Result.success(it)
            }
            // Stiller Fallback: keine Fehlermeldung im Diktier-Flow, direkt Cloud
            recordLocalDiag("Cloud-Fallback — ${local.exceptionOrNull()?.message?.take(90) ?: "unbekannter Fehler"}")
            showStatus("Transkribiere...", Color.parseColor("#8E8E93"))
        } else {
            val reason = when {
                !flagOn     -> "Schalter aus"
                !durationOk -> "Aufnahme über 30s"
                else        -> "Modell fehlt oder unvollständig"
            }
            recordLocalDiag("Cloud — $reason")
        }

        if (openAiKey.isBlank()) {
            return Result.failure(Exception("Kein OpenAI API-Key — bitte in der Laberboombox-App eintragen"))
        }
        if (trackCost) CostTracker.recordAudio(durationMs / 1000L, this)
        return WhisperClient.transcribe(file, openAiKey, whisperLanguage)
    }

    private suspend fun processAudio(file: File, durationMs: Long) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAiKey = (prefs.getString(KEY_OPENAI_API_KEY, "") ?: "").trim()
        val anthropicKey = (prefs.getString(KEY_ANTHROPIC_API_KEY, "") ?: "").trim()
        val profile = prefs.getString(KEY_STYLE_PROFILE, PROFILE_WHATSAPP) ?: PROFILE_WHATSAPP
        val language = (prefs.getString(KEY_LANGUAGE, "") ?: "").trim()
        val whisperLanguage = if (language == "platt") "de" else language
        val emojiLevel = prefs.getString(KEY_EMOJI_LEVEL, EMOJI_FEW) ?: EMOJI_FEW
        val emojiEnabled = prefs.getBoolean(KEY_EMOJI_ENABLED, true)
        val effectiveEmojiLevel = if (emojiEnabled) emojiLevel else EMOJI_NONE
        val headingsEnabled = prefs.getBoolean(KEY_HEADINGS_ENABLED, true)

        val transcription = smartTranscribe(file, whisperLanguage, durationMs, openAiKey, trackCost = true)
            .getOrElse {
                showToast(
                    if (it.message?.contains("API-Key") == true) it.message ?: "Kein OpenAI API-Key"
                    else "Whisper-Fehler: ${it.message?.take(60)}"
                )
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
                PROFILE_FORMAL       -> "Formal"
                else                 -> "WhatsApp"
            }
            showStatus("Korrigiere [$profileLabel]...", Color.parseColor("#8E8E93"))
            val systemPrompt = StylePrompts.get(effectiveProfile, effectiveEmojiLevel, headingsEnabled, language)
            CostTracker.recordClaude(this)
            ClaudeClient.correct(transcription, systemPrompt, anthropicKey)
                .getOrDefault(transcription)
        } else {
            transcription
        }).stripDictationPrefix().replacePunctuationWords()

        withContext(Dispatchers.Main) {
            if (WhisperAccessibilityService.isRunning) {
                hideStatus()
                showPreviewOverlay(finalText)
            } else {
                showStatus("Schritt 4 aktivieren!", Color.parseColor("#FF3B30"))
                hideStatus(4000)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("whisperflow", finalText))
            }
        }
    }

    // ── Korrektur-Vorschau (Bottom Sheet) ────────────────────────────────────

    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("(?<=[.!?])\\s+")
        val parts = text.split(regex)
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) result.add(trimmed)
        }
        return if (result.isEmpty()) listOf(text) else result
    }

    private fun showPreviewOverlay(inputText: String) {
        hidePreviewOverlay()
        undoStack.clear()
        val dp = resources.displayMetrics.density
        val sw = getScreenWidth()
        val sh = getScreenHeight()
        val sheetH = (sh * 0.75f).toInt()

        previewSentences = splitIntoSentences(inputText).toMutableList()
        selectedSentenceIndex = -1
        sentenceViews.clear()

        // Root layout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(24*dp, 24*dp, 24*dp, 24*dp, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#1C1C1E"))
            }
            elevation = 24f * dp
        }

        // Drag handle
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3 * dp
                setColor(Color.parseColor("#48484A"))
            }
        }
        val handleParams = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (12 * dp).toInt()
            bottomMargin = (8 * dp).toInt()
        }
        root.addView(handle, handleParams)

        // Header row: title + X button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }
        val titleTv = TextView(this).apply {
            text = "Text prüfen"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { hidePreviewOverlay() }
        }
        header.addView(titleTv)
        header.addView(closeBtn)
        root.addView(header)

        // Divider
        val divider = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#2C2C2E")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
        }
        root.addView(divider)

        // Scroll area with sentences
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
        }
        val sentenceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }

        previewSentences.forEachIndexed { idx, sentence ->
            val tv = TextView(this).apply {
                text = sentence
                textSize = 16f
                setTextColor(Color.WHITE)
                setLineSpacing(3 * dp, 1f)
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10 * dp
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener { selectSentence(idx) }
            }
            val tvParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
            sentenceContainer.addView(tv, tvParams)
            sentenceViews.add(tv)
        }
        scroll.addView(sentenceContainer)
        root.addView(scroll)

        // Action row (initially hidden, shown on sentence select)
        val aRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setBackgroundColor(Color.parseColor("#2C2C2E"))
            gravity = Gravity.CENTER_VERTICAL
        }
        val deleteBtn = TextView(this).apply {
            text = "🗑 Löschen"
            textSize = 14f
            setTextColor(Color.parseColor("#FF453A"))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                setColor(Color.parseColor("#1AFF453A"))
            }
            setOnClickListener { deleteSelectedSentence() }
        }
        val editBtn = TextView(this).apply {
            text = "✏️ Bearbeiten"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                setColor(Color.parseColor("#2C2C2E"))
                setStroke((1 * dp).toInt(), Color.parseColor("#48484A"))
            }
            setOnClickListener { toggleWordEdit(this) }
        }
        val rerecordBtn = TextView(this).apply {
            text = "🎤 Neu sprechen"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                setColor(Color.parseColor("#2C2C2E"))
                setStroke((1 * dp).toInt(), Color.parseColor("#48484A"))
            }
            setOnClickListener { startMiniRecording(rerecordBtn = this) }
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        aRow.addView(deleteBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dp).toInt() })
        aRow.addView(editBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dp).toInt() })
        aRow.addView(rerecordBtn)
        aRow.addView(spacer)
        root.addView(aRow)
        actionRow = aRow

        // ── Permanente Toolbar: Rückgängig + Kopieren + Swipe-Hint ───────────────
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setBackgroundColor(Color.parseColor("#111113"))
        }

        val uBtn = TextView(this).apply {
            this.text = "↩"
            textSize = 18f
            setTextColor(Color.WHITE)
            alpha = 0.35f
            isClickable = false
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                setColor(Color.parseColor("#2C2C2E"))
            }
            setOnClickListener { undoDeleteSentence() }
        }
        undoBtn = uBtn

        val copyBtn = TextView(this).apply {
            this.text = "⎘"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                setColor(Color.parseColor("#2C2C2E"))
            }
            setOnClickListener {
                finishWordEdit()
                val textToCopy = previewSentences.joinToString(" ")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("whisperflow", textToCopy))
                this.text = "✓"
                setTextColor(Color.parseColor("#32D74B"))
                Handler(Looper.getMainLooper()).postDelayed({
                    this.text = "⎘"
                    setTextColor(Color.WHITE)
                }, 1500)
            }
        }

        val toolbarSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val swipeHint = TextView(this).apply {
            this.text = "↑ Wischen zum Einfügen"
            textSize = 12f
            setTextColor(Color.parseColor("#636366"))
            gravity = Gravity.CENTER_VERTICAL
        }

        toolbar.addView(uBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dp).toInt() })
        toolbar.addView(copyBtn)
        toolbar.addView(toolbarSpacer)
        toolbar.addView(swipeHint)
        root.addView(toolbar)

        // Swipe-to-send gesture on the whole root
        var touchDownY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { touchDownY = event.rawY; false }
                MotionEvent.ACTION_UP -> {
                    val dy = touchDownY - event.rawY
                    if (dy > 80 * dp) {
                        // Swipe up → insert
                        finishWordEdit()
                        val finalText = previewSentences.joinToString(" ")
                        hidePreviewOverlay()
                        WhisperAccessibilityService.inject(finalText)
                        true
                    } else false
                }
                else -> false
            }
        }

        // WindowManager params for bottom sheet
        val pp = WindowManager.LayoutParams(
            sw,
            sheetH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
        }

        previewBottomSheet = root
        previewView = root
        previewSheetParams = pp
        windowManager.addView(root, pp)

        // Slide-in animation
        root.translationY = sheetH.toFloat()
        root.animate()
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    private fun selectSentence(idx: Int) {
        finishWordEdit()
        val dp = resources.displayMetrics.density
        // Deselect previous
        if (selectedSentenceIndex >= 0) {
            sentenceViews.getOrNull(selectedSentenceIndex)?.apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10 * dp
                    setColor(Color.TRANSPARENT)
                }
                setTextColor(Color.WHITE)
            }
        }
        if (selectedSentenceIndex == idx) {
            // Toggle off
            selectedSentenceIndex = -1
            actionRow?.visibility = View.GONE
            return
        }
        selectedSentenceIndex = idx
        sentenceViews.getOrNull(idx)?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(Color.parseColor("#33FFD60A"))
                setStroke((1 * dp).toInt(), Color.parseColor("#FFD60A"))
            }
            setTextColor(Color.parseColor("#FFD60A"))
        }
        actionRow?.visibility = View.VISIBLE
    }

    private fun deleteSelectedSentence() {
        finishWordEdit()
        val idx = selectedSentenceIndex
        if (idx < 0 || idx >= previewSentences.size) return
        undoStack.addLast(Pair(idx, previewSentences[idx]))
        previewSentences.removeAt(idx)
        sentenceViews.getOrNull(idx)?.let { tv ->
            (tv.parent as? android.view.ViewGroup)?.removeView(tv)
        }
        sentenceViews.removeAt(idx)
        selectedSentenceIndex = -1
        actionRow?.visibility = View.GONE
        updateUndoButton()
    }

    private fun updateUndoButton() {
        val active = undoStack.isNotEmpty()
        undoBtn?.apply {
            alpha = if (active) 1f else 0.35f
            isClickable = active
        }
    }

    private fun undoDeleteSentence() {
        if (undoStack.isEmpty()) return
        val (idx, text) = undoStack.removeLast()
        val insertAt = idx.coerceIn(0, previewSentences.size)
        previewSentences.add(insertAt, text)

        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setLineSpacing(3 * dp, 1f)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(Color.TRANSPARENT)
            }
            val newIdx = insertAt
            setOnClickListener { selectSentence(newIdx) }
        }
        val tvParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (6 * dp).toInt() }

        // Finde sentenceContainer (Parent der sentenceViews)
        val container = sentenceViews.firstOrNull()?.parent as? LinearLayout
            ?: sentenceViews.lastOrNull()?.parent as? LinearLayout
        container?.addView(tv, insertAt, tvParams)
        sentenceViews.add(insertAt, tv)
        updateUndoButton()
    }

    // ── Wort-Editor: Satz per System-Tastatur statt per Sprache korrigieren ──────

    private fun toggleWordEdit(editBtn: TextView) {
        if (editingSentenceIndex >= 0) {
            commitWordEdit()
        } else {
            startWordEdit(editBtn)
        }
    }

    private fun startWordEdit(editBtn: TextView) {
        if (isMiniRecording) return
        val idx = selectedSentenceIndex
        val tv = sentenceViews.getOrNull(idx) ?: return
        val container = tv.parent as? LinearLayout ?: return
        val dp = resources.displayMetrics.density

        val et = EditText(this).apply {
            setText(previewSentences.getOrNull(idx) ?: tv.text)
            setSelection(text.length)
            textSize = 16f
            setTextColor(Color.parseColor("#FFD60A"))
            setHintTextColor(Color.parseColor("#8E8E93"))
            setBackgroundColor(Color.TRANSPARENT)
            setLineSpacing(3 * dp, 1f)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(false)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { commitWordEdit(); true } else false
            }
        }
        val pos = container.indexOfChild(tv)
        container.removeViewAt(pos)
        container.addView(et, pos, tv.layoutParams)
        sentenceViews[idx] = et

        editingSentenceIndex = idx
        editingEditText = et
        editingEditBtn = editBtn
        editBtn.text = "✓ Fertig"
        editBtn.setTextColor(Color.parseColor("#32D74B"))

        // Fenster muss kurzzeitig fokussierbar werden, sonst bekommt das EditText nie den
        // Fokus und die Tastatur bleibt unten — Overlay-Fenster sind sonst bewusst
        // FLAG_NOT_FOCUSABLE, damit sie der Vordergrund-App nie den Fokus wegnehmen.
        val sheet = previewBottomSheet
        val p = previewSheetParams
        if (sheet != null && p != null) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            runCatching { windowManager.updateViewLayout(sheet, p) }
        }
        et.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun commitWordEdit() {
        val idx = editingSentenceIndex
        val et = editingEditText
        val editBtn = editingEditBtn
        if (idx < 0 || et == null) return

        val newText = et.text.toString()
        if (idx < previewSentences.size) previewSentences[idx] = newText

        val dp = resources.displayMetrics.density
        val isSelected = selectedSentenceIndex == idx
        val tv = TextView(this).apply {
            text = newText
            textSize = 16f
            setTextColor(if (isSelected) Color.parseColor("#FFD60A") else Color.WHITE)
            setLineSpacing(3 * dp, 1f)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                if (isSelected) {
                    setColor(Color.parseColor("#33FFD60A"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#FFD60A"))
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
            setOnClickListener { selectSentence(idx) }
        }
        val container = et.parent as? LinearLayout
        val pos = container?.indexOfChild(et) ?: -1
        if (container != null && pos >= 0) {
            container.removeViewAt(pos)
            container.addView(tv, pos, et.layoutParams)
        }
        if (idx < sentenceViews.size) sentenceViews[idx] = tv

        editingSentenceIndex = -1
        editingEditText = null
        editingEditBtn = null
        editBtn?.text = "✏️ Bearbeiten"
        editBtn?.setTextColor(Color.WHITE)

        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(et.windowToken, 0)
        }
        val sheet = previewBottomSheet
        val p = previewSheetParams
        if (sheet != null && p != null) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager.updateViewLayout(sheet, p) }
        }
    }

    private fun finishWordEdit() {
        if (editingSentenceIndex >= 0) commitWordEdit()
    }

    private fun startMiniRecording(rerecordBtn: TextView) {
        if (isMiniRecording) {
            stopMiniRecording(rerecordBtn)
            return
        }
        finishWordEdit()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAiKey = (prefs.getString(KEY_OPENAI_API_KEY, "") ?: "").trim()
        val localAvailable = prefs.getBoolean(KEY_ONDEVICE_WHISPER, false) &&
            ModelManager.isModelAvailable(this)
        if (openAiKey.isBlank() && !localAvailable) return

        // Ziel-Satz jetzt festhalten (nicht erst beim Stoppen) — sonst landet die Korrektur
        // am falschen Satz, falls während der Aufnahme versehentlich ein anderer angetippt wird.
        miniRecordingTargetIdx = selectedSentenceIndex
        miniRecordingStartTime = System.currentTimeMillis()
        miniRecordingFile = File(cacheDir, "mini_rec_${System.currentTimeMillis()}.m4a")
        runCatching {
            miniMediaRecorder = createMediaRecorder().apply {
                // VOICE_RECOGNITION: gleiche Rauschunterdrückung wie bei der Hauptaufnahme.
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(miniRecordingFile!!.absolutePath)
                prepare()
                start()
            }
            isMiniRecording = true
            rerecordBtn.setTextColor(Color.parseColor("#FF453A"))
            startPulseRing()

            val startedAt = miniRecordingStartTime
            miniTimerRunnable = object : Runnable {
                override fun run() {
                    if (!isMiniRecording) return
                    val s = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    val time = if (s < 60) "0:${s.toString().padStart(2, '0')}"
                               else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
                    rerecordBtn.text = "⏹ $time"
                    miniTimerHandler.postDelayed(this, 1000)
                }
            }
            rerecordBtn.text = "⏹ 0:00"
            miniTimerHandler.post(miniTimerRunnable!!)
        }

        // Auto-stop after 30s — vorherigen Timer immer zuerst abbrechen, sonst kann ein
        // liegengebliebener Timer der letzten Aufnahme eine spätere Aufnahme verfrüht stoppen.
        miniAutoStopRunnable?.let { miniTimerHandler.removeCallbacks(it) }
        val autoStop = Runnable { if (isMiniRecording) stopMiniRecording(rerecordBtn) }
        miniAutoStopRunnable = autoStop
        miniTimerHandler.postDelayed(autoStop, 30_000)
    }

    private fun stopMiniRecording(rerecordBtn: TextView) {
        if (!isMiniRecording) return
        isMiniRecording = false
        rerecordBtn.text = "🎤 Neu sprechen"
        rerecordBtn.setTextColor(Color.WHITE)
        stopPulseRing()
        miniAutoStopRunnable?.let { miniTimerHandler.removeCallbacks(it) }
        miniAutoStopRunnable = null
        miniTimerRunnable?.let { miniTimerHandler.removeCallbacks(it) }
        miniTimerRunnable = null

        runCatching { miniMediaRecorder?.stop() }
        runCatching { miniMediaRecorder?.release() }
        miniMediaRecorder = null

        val file = miniRecordingFile ?: return
        miniRecordingFile = null

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAiKey = (prefs.getString(KEY_OPENAI_API_KEY, "") ?: "").trim()
        val language = (prefs.getString(KEY_LANGUAGE, "") ?: "").trim()
        val whisperLanguage = if (language == "platt") "de" else language
        val capturedIdx = miniRecordingTargetIdx
        val miniDurationMs = (System.currentTimeMillis() - miniRecordingStartTime).coerceAtLeast(0)

        serviceScope.launch {
            val result = smartTranscribe(file, whisperLanguage, miniDurationMs, openAiKey, trackCost = true)
            file.delete()
            result.onSuccess { newText ->
                val cleaned = newText.trim().removeFillWords().replacePunctuationWords()
                withContext(Dispatchers.Main) {
                    if (capturedIdx >= 0 && capturedIdx < previewSentences.size) {
                        previewSentences[capturedIdx] = cleaned
                        sentenceViews.getOrNull(capturedIdx)?.text = cleaned
                    }
                }
            }
            // smartTranscribe blendet bei On-Device-Whisper "Transkribiere (lokal)..." im
            // Status-Overlay ein — das bleibt sonst dauerhaft stehen, weil dieser Pfad (anders
            // als processAudio) nie hideStatus() aufruft.
            withContext(Dispatchers.Main) { hideStatus() }
        }
    }

    private fun hidePreviewOverlay() {
        if (editingSentenceIndex >= 0) {
            val et = editingEditText
            if (et != null) runCatching {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(et.windowToken, 0)
            }
            editingSentenceIndex = -1
            editingEditText = null
            editingEditBtn = null
        }
        if (isMiniRecording) {
            runCatching { miniMediaRecorder?.stop() }
            runCatching { miniMediaRecorder?.release() }
            miniMediaRecorder = null
            isMiniRecording = false
            stopPulseRing()
            miniAutoStopRunnable?.let { miniTimerHandler.removeCallbacks(it) }
            miniAutoStopRunnable = null
            miniTimerRunnable?.let { miniTimerHandler.removeCallbacks(it) }
            miniTimerRunnable = null
            miniRecordingFile?.delete()
            miniRecordingFile = null
        }
        runCatching { previewView?.let { windowManager.removeView(it) } }
        previewView = null
        previewBottomSheet = null
        previewSheetParams = null
        sentenceViews.clear()
        previewSentences.clear()
        selectedSentenceIndex = -1
        actionRow = null
        undoStack.clear()
        undoBtn = null
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
        // Hesitationslaute (immer entfernen)
        replace(Regex("""\b(ähm|äh|öhm|öh|hm|hmm|ehm|ehh|ähh|ähähm)\b[,\s]*""", RegexOption.IGNORE_CASE), " ")
        // "also" nur am absoluten Satzanfang als bedeutungslose Einleitung
        .replace(Regex("""^also[,\s]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""(?<=[.!?]\s)also[,\s]+""", RegexOption.IGNORE_CASE), "")
        // "genau genau", "ja genau", "genau" als isolierte Bestätigung
        .replace(Regex("""\b(ja\s+)?genau\s+genau\b[,\s]*""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\bja\s+genau\b[,\s]*""", RegexOption.IGNORE_CASE), " ")
        // "ne" / "oder" am Satzende als Füllsel (nur wenn direkt vor ? oder am Ende)
        .replace(Regex(""",?\s*\bne\b\s*\?"""), "?")
        .replace(Regex(""",?\s*\bne\b\s*${'$'}""", RegexOption.MULTILINE), "")
        // Mehrfache Leerzeichen bereinigen
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

    // ── Duration Badges ───────────────────────────────────────────────────────

    private fun setupDurationBadges() {
        val dp = resources.displayMetrics.density
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentMaxSeconds = prefs.getInt(KEY_MAX_DURATION, 30)

        val badgeW = (52 * dp).toInt()
        val badgeH = (22 * dp).toInt()
        val miniW  = (32 * dp).toInt()
        val gap    = (5 * dp).toInt()

        // ── Bottom badge: duration cycle ──────────────────────────────────────
        val dBadge = TextView(this).apply {
            text = durationLabel(currentMaxSeconds)
            textSize = 10.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            background = buildBadgeBg(dp, active = false)
        }
        val dParams = overlayParams(
            w = badgeW, h = badgeH,
            x = params.x + (buttonSize - badgeW) / 2,
            y = params.y + buttonSize + gap
        )
        dBadge.setOnClickListener {
            val next = when (currentMaxSeconds) {
                30   -> 90
                90   -> 180
                180  -> 300
                else -> 30
            }
            applyDuration(next)
        }
        durationBadgeView   = dBadge
        durationBadgeParams = dParams
        windowManager.addView(dBadge, dParams)

        // ── Top badge: ⚡ Mini shortcut ────────────────────────────────────────
        val mBadge = TextView(this).apply {
            text = "⚡"
            textSize = 12f
            gravity = Gravity.CENTER
            background = buildBadgeBg(dp, active = currentMaxSeconds == DURATION_MINI)
            setTextColor(if (currentMaxSeconds == DURATION_MINI) Color.parseColor("#1C1C1E") else Color.WHITE)
        }
        val mParams = overlayParams(
            w = miniW, h = badgeH,
            x = params.x + (buttonSize - miniW) / 2,
            y = params.y - badgeH - gap
        )
        mBadge.setOnClickListener { applyDuration(DURATION_MINI) }
        miniBadgeView   = mBadge
        miniBadgeParams = mParams
        windowManager.addView(mBadge, mParams)

        // ── Side badge: emoji on/off ──────────────────────────────────────────
        val emojiW = (32 * dp).toInt()
        val emojiH = (32 * dp).toInt()
        val emojiOn = prefs.getBoolean(KEY_EMOJI_ENABLED, true)
        val eBadge = TextView(this).apply {
            text = if (emojiOn) "🙂" else "—"
            textSize = if (emojiOn) 16f else 14f
            gravity = Gravity.CENTER
            background = buildBadgeBg(dp, active = emojiOn)
            setTextColor(if (emojiOn) Color.parseColor("#1C1C1E") else Color.parseColor("#8E8E93"))
        }
        val eParams = overlayParams(
            w = emojiW, h = emojiH,
            x = emojiBadgeX(emojiW),
            y = params.y + (buttonSize - emojiH) / 2
        )
        eBadge.setOnClickListener { toggleEmojiBadge() }
        emojiBadgeView   = eBadge
        emojiBadgeParams = eParams
        windowManager.addView(eBadge, eParams)
    }

    private fun emojiBadgeX(badgeW: Int): Int {
        val dp = resources.displayMetrics.density
        val gap = (6 * dp).toInt()
        return if (isOnRightEdge) params.x - badgeW - gap
               else params.x + buttonSize + gap
    }

    private fun toggleEmojiBadge() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_EMOJI_ENABLED, true)
        val next = !current
        prefs.edit().putBoolean(KEY_EMOJI_ENABLED, next).apply()
        val dp = resources.displayMetrics.density
        emojiBadgeView?.apply {
            text = if (next) "🙂" else "—"
            textSize = if (next) 16f else 14f
            background = buildBadgeBg(dp, active = next)
            setTextColor(if (next) Color.parseColor("#1C1C1E") else Color.parseColor("#8E8E93"))
            animate().cancel()
            animate().scaleX(0.82f).scaleY(0.82f).setDuration(70)
                .withEndAction {
                    animate().scaleX(1f).scaleY(1f)
                        .setDuration(140)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }.start()
        }
        resetInactivityTimer()
    }

    private fun applyDuration(seconds: Int) {
        currentMaxSeconds = seconds
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_DURATION, seconds).apply()

        val dp = resources.displayMetrics.density
        val isMini = seconds == DURATION_MINI

        durationBadgeView?.text = durationLabel(seconds)

        miniBadgeView?.apply {
            background = buildBadgeBg(dp, active = isMini)
            setTextColor(if (isMini) Color.parseColor("#1C1C1E") else Color.WHITE)
        }

        resetInactivityTimer()

        // subtle pulse feedback on both badges
        listOfNotNull(durationBadgeView, miniBadgeView).forEach { v ->
            v.animate().cancel()
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                .withEndAction {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }.start()
        }
    }

    private fun buildBadgeBg(dp: Float, active: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 11 * dp
            if (active) {
                setColor(Color.parseColor("#FFD60A"))
                setStroke((1 * dp).toInt(), Color.parseColor("#CC7A00"))
            } else {
                setColor(Color.parseColor("#CC1C1C1E"))
                setStroke((1 * dp).toInt(), Color.parseColor("#30FFFFFF"))
            }
        }

    private fun durationLabel(seconds: Int): String = when (seconds) {
        DURATION_MINI -> "⚡"
        30            -> "30s"
        90            -> "90s"
        180           -> "3m"
        300           -> "5m"
        else          -> "${seconds}s"
    }

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn(0, (getScreenWidth() - w).coerceAtLeast(0))
            this.y = y.coerceAtLeast(0)
        }

    private fun updateBadgePositions() {
        val dp = resources.displayMetrics.density
        val gap = (5 * dp).toInt()
        val sw = getScreenWidth()
        durationBadgeParams?.let { p ->
            val w = p.width
            p.x = (params.x + (buttonSize - w) / 2).coerceIn(0, (sw - w).coerceAtLeast(0))
            p.y = params.y + buttonSize + gap
            durationBadgeView?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
        miniBadgeParams?.let { p ->
            val w = p.width
            val h = p.height
            p.x = (params.x + (buttonSize - w) / 2).coerceIn(0, (sw - w).coerceAtLeast(0))
            p.y = (params.y - h - gap).coerceAtLeast(0)
            miniBadgeView?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
        emojiBadgeParams?.let { p ->
            val w = p.width
            val h = p.height
            p.x = emojiBadgeX(w).coerceIn(0, (sw - w).coerceAtLeast(0))
            p.y = params.y + (buttonSize - h) / 2
            emojiBadgeView?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
    }

    private fun showBadges() {
        listOfNotNull(durationBadgeView, miniBadgeView, emojiBadgeView).forEach { v ->
            v.animate().cancel()
            v.alpha = 0f
            v.scaleX = 0.82f
            v.scaleY = 0.82f
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun hideBadges() {
        listOfNotNull(durationBadgeView, miniBadgeView, emojiBadgeView).forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f)
                .setDuration(140)
                .withEndAction { v.visibility = View.GONE }
                .start()
        }
    }

    private fun removeBadges() {
        runCatching { durationBadgeView?.let { windowManager.removeView(it) } }
        durationBadgeView   = null
        durationBadgeParams = null
        runCatching { miniBadgeView?.let { windowManager.removeView(it) } }
        miniBadgeView   = null
        miniBadgeParams = null
        runCatching { emojiBadgeView?.let { windowManager.removeView(it) } }
        emojiBadgeView   = null
        emojiBadgeParams = null
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private suspend fun showToast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@FloatingButtonService, text, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else MediaRecorder()

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "Laberboombox", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Laberboombox")
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
