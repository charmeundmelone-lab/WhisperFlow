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
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root)
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                handler.postDelayed({
                    node.refresh()
                    // Read existing field content and append. WhatsApp and many apps only
                    // accept ACTION_SET_TEXT if the new text extends what's already there.
                    // The dictation prefix bugs are fixed upstream (Claude <diktat> tags +
                    // stripDictationPrefix), so existing content should be clean.
                    val existing = node.text?.toString()?.trimEnd() ?: ""
                    val combined = if (existing.isEmpty()) text else "$existing $text"
                    val bundle = android.os.Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            combined
                        )
                    }
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    if (!ok) {
                        // Fallback: clipboard + paste (works in WhatsApp etc.; in Gmail
                        // the text lands in clipboard so the user can long-press → Einfügen)
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("whisperflow", text))
                        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }
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
