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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { pkg ->
            if (pkg.isNotEmpty()) activePackage = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isRunning = false
        instance = null
        super.onDestroy()
    }

    fun injectText(text: String) {
        handler.post {
            val root = rootInActiveWindow ?: return@post
            var node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root)
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                handler.postDelayed({
                    node.refresh()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("whisperflow", text))
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }, 150)
            }
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    companion object {
        var isRunning = false
            private set
        var activePackage = ""
            private set
        private var instance: WhisperAccessibilityService? = null

        fun inject(text: String) {
            instance?.injectText(text)
        }
    }
}
