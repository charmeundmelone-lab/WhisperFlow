package de.minitraxx.whisperflow.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhisperAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        isRunning = true
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        isRunning = false
        instance = null
        super.onDestroy()
    }

    fun injectText(text: String) {
        handler.post {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("whisperflow", text))
            rootInActiveWindow
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    companion object {
        var isRunning = false
            private set
        private var instance: WhisperAccessibilityService? = null

        fun inject(text: String) {
            instance?.injectText(text)
        }
    }
}
