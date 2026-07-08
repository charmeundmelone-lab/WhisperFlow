package de.minitraxx.whisperflow.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
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
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank()) return
        if (pkg == "de.minitraxx.whisperflow") return
        if (pkg.startsWith("com.android.") || pkg == "android") return
        activePackage = pkg
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
                ?: findBottomMostEditable(root)
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                handler.postDelayed({
                    node.refresh()
                    val bundle = android.os.Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
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

    // Prüft ohne Seiteneffekt, ob es im Vordergrund ein editierbares Feld gibt —
    // genutzt vom Parkplatz-Fallback, um vor dem Einfügen zu entscheiden, ob der
    // Text ins Feld geht oder (mangels Ziel) in den Parkplatz wandert.
    fun hasEditableTarget(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findBottomMostEditable(root)
        return node != null
    }

    // Finds the editable field with the highest Y position on screen (= bottom-most).
    // In chat apps the compose field is always at the bottom, so this is more reliable
    // than depth-first search when FOCUS_INPUT is lost (e.g. WhatsApp backgrounded).
    private fun findBottomMostEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        collectEditables(root, candidates)
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun collectEditables(node: AccessibilityNodeInfo, result: MutableList<Pair<AccessibilityNodeInfo, Int>>) {
        if (node.isEditable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            result.add(node to bounds.top)
        }
        for (i in 0 until node.childCount) {
            collectEditables(node.getChild(i) ?: return, result)
        }
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

        fun hasEditableTarget(): Boolean = instance?.hasEditableTarget() ?: false
    }
}
