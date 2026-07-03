package de.minitraxx.whisperflow.whisper

/**
 * Dünne JNI-Schicht zu whisper.cpp (libwhisperflow_jni.so).
 *
 * Die native Lib wird NIEMALS beim App-Start geladen, sondern erst beim ersten
 * tatsächlichen On-Device-Aufruf — immer in runCatching. Ein Fehler hier darf
 * den bestehenden Cloud-Pfad nie beeinträchtigen.
 */
internal object WhisperJni {

    @Volatile
    private var loadState: Boolean? = null

    @Synchronized
    fun ensureLoaded(): Boolean {
        loadState?.let { return it }
        val ok = runCatching { System.loadLibrary("whisperflow_jni") }.isSuccess
        loadState = ok
        return ok
    }

    external fun nativeInit(modelPath: String): Long

    external fun nativeTranscribe(
        ctxPtr: Long,
        samples: FloatArray,
        language: String,
        nThreads: Int,
        initialPrompt: String,
        audioCtx: Int
    ): String?

    external fun nativeFree(ctxPtr: Long)

    /** true = CPU + Build unterstützen den ARM-Dotprod-Fastpath (sdot/udot). */
    fun hasDotprod(): Boolean = runCatching { ensureLoaded() && nativeHasDotprod() }.getOrDefault(false)

    private external fun nativeHasDotprod(): Boolean
}
