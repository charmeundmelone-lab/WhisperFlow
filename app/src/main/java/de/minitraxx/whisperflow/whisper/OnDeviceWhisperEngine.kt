package de.minitraxx.whisperflow.whisper

/**
 * Laberboombox — on-device Whisper inference (Milestone 2: real whisper.cpp
 * JNI bridge).
 *
 * IMPORTANT — this class is NOT wired into the app yet. Nothing in
 * `FloatingButtonService` or anywhere else in the existing code references
 * this class. That is intentional: the on-device pipeline (chunk buffering,
 * "Fertig" badge, feature flag, cloud fallback) is a later, separate
 * milestone (see CLAUDE.md, "On-Device Whisper — Umsetzungsplan"). Until
 * that milestone wires this up behind a feature flag, this class must stay
 * completely inert so the app behaves 100% as before.
 *
 * Hard safety rule for this class (see CLAUDE.md: "jeder On-Device-Fehler
 * -> automatisch und still auf Cloud-Whisper zurückfallen"): every public
 * method wraps its native call in `runCatching { }` and returns null/false
 * instead of throwing or crashing. The eventual caller (a later milestone)
 * can then fall back to cloud Whisper on any null/false result without
 * needing its own try/catch around this class.
 *
 * The native library is loaded lazily on first use (inside `runCatching`),
 * not in an `init { }` block, so that a missing/broken `.so` can never
 * affect app startup or any code path that doesn't use this class.
 */
class OnDeviceWhisperEngine {

    private var contextHandle: Long = 0L

    /**
     * Loads a GGML/whisper.cpp model from [modelPath] (a plain filesystem
     * path, e.g. in app-internal storage after a user-triggered download —
     * see CLAUDE.md model-download-flow todo).
     *
     * Returns `true` on success. On ANY failure (missing file, corrupt
     * model, native library not loadable, JNI exception, ...) returns
     * `false` and leaves this engine unusable — the caller should treat
     * that as "on-device unavailable, use cloud Whisper for this
     * recording".
     */
    fun loadModel(modelPath: String): Boolean {
        return runCatching {
            NativeWhisperBridge.ensureLoaded()
            val handle = NativeWhisperBridge.nativeLoadModel(modelPath)
            contextHandle = handle
            handle != 0L
        }.getOrDefault(false)
    }

    /**
     * Transcribes [pcm] — 16kHz mono 32-bit float PCM audio, matching
     * whisper.cpp's expected input format — using the model previously
     * loaded via [loadModel].
     *
     * [language] is an optional whisper language code (e.g. "de", "en").
     * Null or blank means auto-detect.
     *
     * Returns the transcribed text, or `null` on any failure (no model
     * loaded, native exception, empty/invalid audio, ...). A `null` result
     * signals the caller to fall back to cloud Whisper for this recording.
     */
    fun transcribe(pcm: FloatArray, language: String? = null): String? {
        return runCatching {
            val handle = contextHandle
            if (handle == 0L || pcm.isEmpty()) {
                return@runCatching null
            }
            NativeWhisperBridge.ensureLoaded()
            NativeWhisperBridge.nativeTranscribe(handle, pcm, language.orEmpty())
        }.getOrNull()
    }

    /**
     * Releases native resources held by this engine. Safe to call multiple
     * times and safe to call even if [loadModel] was never called or failed.
     */
    fun release() {
        runCatching {
            val handle = contextHandle
            if (handle != 0L) {
                NativeWhisperBridge.nativeFreeContext(handle)
            }
        }
        contextHandle = 0L
    }
}

/**
 * Internal holder for the raw `external fun` declarations and lazy native
 * library loading.
 *
 * Kept as a plain top-level `object` (mirroring
 * `de.minitraxx.whisperflow.nativebridge.WhisperBridge`) rather than a
 * companion object, because `external fun`s declared on a Kotlin companion
 * object get an extra `$Companion` (`00024Companion`) infix in their JNI
 * symbol name — a plain object keeps the C++ side (`whisper-jni.cpp`)
 * simpler and less error-prone to match.
 */
private object NativeWhisperBridge {

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) {
            return
        }
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("whisperbridge")
                loaded = true
            }
        }
    }

    /**
     * Loads a GGML model from [modelPath] and returns a native context
     * handle, or 0 on failure. The handle must eventually be released via
     * [nativeFreeContext].
     */
    external fun nativeLoadModel(modelPath: String): Long

    /**
     * Transcribes 16kHz mono float PCM audio using the model behind
     * [contextHandle]. [language] is a whisper language code, or an empty
     * string for auto-detect. Returns the transcribed text.
     */
    external fun nativeTranscribe(
        contextHandle: Long,
        pcmAudio: FloatArray,
        language: String
    ): String?

    /**
     * Frees native resources associated with [contextHandle].
     */
    external fun nativeFreeContext(contextHandle: Long)
}
