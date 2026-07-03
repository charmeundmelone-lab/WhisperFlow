package de.minitraxx.whisperflow.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * On-Device-Transkription über whisper.cpp.
 *
 * Sicherheitsprinzip (siehe CLAUDE.md): JEDER Fehler hier führt zu einem
 * Result.failure — der Aufrufer fällt dann still auf Cloud-Whisper zurück.
 * Die native Lib und das Modell werden lazy geladen, nie beim App-Start.
 */
object LocalWhisperEngine {

    private const val TAG = "LocalWhisperEngine"

    /** Gleicher Kontext-Prompt wie beim Cloud-Whisper-Aufruf. */
    private const val INITIAL_PROMPT = "Gesprochener Text, direkt transkribiert."

    /** Harte Obergrenze: kommt die Engine nicht zurück, übernimmt die Cloud. */
    private const val TRANSCRIBE_TIMEOUT_MS = 90_000L

    // Eigener Scope: läuft die native Inferenz in einen Timeout, arbeitet sie im
    // Hintergrund zu Ende (nativer Code ist nicht abbrechbar) und gibt danach
    // die Engine über `busy` wieder frei — der Aufrufer ist längst bei der Cloud.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)

    @Volatile
    private var ctxPtr: Long = 0L

    @Volatile
    private var loadedModelPath: String? = null

    /** Grobe Phasen-Markierung für aussagekräftige Timeout-Diagnosen. */
    @Volatile
    private var phase: String = "?"

    /**
     * Transkribiert [audioFile] (M4A) lokal. [language] wie beim Cloud-Aufruf:
     * ""/blank = automatische Erkennung, sonst ISO-Code ("de", "en", ...).
     */
    suspend fun transcribe(context: Context, audioFile: File, language: String): Result<String> {
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Engine beschäftigt"))
        }
        val appContext = context.applicationContext
        val deferred = engineScope.async {
            try {
                runCatching { doTranscribe(appContext, audioFile, language) }
            } finally {
                busy.set(false)
            }
        }
        return withTimeoutOrNull(TRANSCRIBE_TIMEOUT_MS) { deferred.await() }
            ?: Result.failure(IllegalStateException("On-Device-Timeout in Phase: $phase"))
    }

    private fun doTranscribe(context: Context, audioFile: File, language: String): String {
        phase = "Lib-Laden"
        check(WhisperJni.ensureLoaded()) { "Native Lib nicht ladbar" }

        val modelFile = ModelManager.selectedModelFile(context)
        check(modelFile.exists() && modelFile.length() > 0) { "Modell fehlt" }

        phase = "Audio-Dekodierung"
        val samples = AudioDecoder.decodeToWhisperPcm(audioFile)
        check(samples.isNotEmpty()) { "Keine Audio-Samples" }

        // audio_ctx-Optimierung: Encoder nur so groß rechnen wie das Audio wirklich ist
        // (1500 Tokens = 30s). +128 Sicherheitsmarge; 0 = whisper-Default (volles Fenster).
        val lenSeconds = samples.size.toDouble() / AudioDecoder.WHISPER_SAMPLE_RATE
        val audioCtx = if (lenSeconds >= 28.0) 0
            else (Math.ceil(lenSeconds / 30.0 * 1500.0).toInt() + 128).coerceIn(192, 1500)

        phase = "Modell-Laden"
        synchronized(this) {
            if (ctxPtr == 0L || loadedModelPath != modelFile.absolutePath) {
                if (ctxPtr != 0L) {
                    runCatching { WhisperJni.nativeFree(ctxPtr) }
                    ctxPtr = 0L
                }
                val ptr = WhisperJni.nativeInit(modelFile.absolutePath)
                check(ptr != 0L) { "Modell konnte nicht geladen werden" }
                ctxPtr = ptr
                loadedModelPath = modelFile.absolutePath
            }
        }

        phase = "Inferenz"
        val threads = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
        val start = System.currentTimeMillis()
        val text = WhisperJni.nativeTranscribe(ctxPtr, samples, language.trim(), threads, INITIAL_PROMPT, audioCtx)
            ?: throw IllegalStateException("Native Transkription fehlgeschlagen")
        Log.i(TAG, "Lokale Transkription (${modelFile.name}, audio_ctx=$audioCtx): ${samples.size / AudioDecoder.WHISPER_SAMPLE_RATE}s Audio in ${System.currentTimeMillis() - start}ms")
        return text.trim()
    }

    /** Gibt den nativen Kontext frei (Service-Ende, Modell gelöscht). */
    fun release() {
        synchronized(this) {
            if (ctxPtr != 0L) {
                runCatching { WhisperJni.nativeFree(ctxPtr) }
                ctxPtr = 0L
                loadedModelPath = null
            }
        }
    }
}
