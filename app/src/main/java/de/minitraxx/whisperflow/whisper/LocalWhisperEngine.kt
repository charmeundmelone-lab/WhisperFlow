package de.minitraxx.whisperflow.whisper

import android.content.Context
import android.util.Log
import de.minitraxx.whisperflow.api.WhisperPrompts
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

    /** Wie lange die aktuelle Phase schon läuft, wenn der Timeout zuschlägt. */
    @Volatile
    private var phaseStartedAt: Long = 0L

    /** Welches Modell gerade läuft — steht mit in der Timeout-Diagnose. */
    @Volatile
    private var runningModelId: String = "?"

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
            ?: run {
                val stuckMs = System.currentTimeMillis() - phaseStartedAt
                val dotprod = WhisperJni.hasDotprod()
                Result.failure(IllegalStateException(
                    "On-Device-Timeout: Modell=$runningModelId, Phase='$phase' (${stuckMs}ms), dotprod=$dotprod"
                ))
            }
    }

    private fun doTranscribe(context: Context, audioFile: File, language: String): String {
        // Jede Phase wird sofort geloggt (nicht erst am Ende) — bei einem Timeout
        // kommt der Code nie zum finalen Log, aber die einzelnen mark()-Zeilen
        // sind schon in logcat sichtbar und zeigen exakt, wo die Zeit hingeht.
        val t0 = System.currentTimeMillis()
        fun mark(label: String) {
            Log.i(TAG, "Phase '$label' abgeschlossen nach ${System.currentTimeMillis() - t0}ms gesamt")
        }
        fun enterPhase(label: String) {
            phase = label
            phaseStartedAt = System.currentTimeMillis()
        }

        enterPhase("Lib-Laden")
        check(WhisperJni.ensureLoaded()) { "Native Lib nicht ladbar" }
        mark("Lib-Laden")

        val modelFile = ModelManager.selectedModelFile(context)
        check(modelFile.exists() && modelFile.length() > 0) { "Modell fehlt" }
        runningModelId = ModelManager.selectedModel(context).id

        enterPhase("Audio-Dekodierung")
        val samples = AudioDecoder.decodeToWhisperPcm(audioFile)
        check(samples.isNotEmpty()) { "Keine Audio-Samples" }
        mark("Audio-Dekodierung")

        // Kein audio_ctx-Beschnitt mehr: der Encoder rechnet immer das volle 30s-Fenster.
        // War ein Speed-Hack aus der Debug-Build-Zeit und kostete Genauigkeit,
        // besonders in den letzten Sekunden einer Aufnahme.
        val lenSeconds = samples.size.toDouble() / AudioDecoder.WHISPER_SAMPLE_RATE

        val threads = min(6, Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
        val modelAlreadyLoaded = ctxPtr != 0L && loadedModelPath == modelFile.absolutePath
        Log.i(TAG, "Start: Modell=${modelFile.name} (${modelFile.length() / 1_000_000}MB), " +
            "Audio=${"%.1f".format(lenSeconds)}s, threads=$threads, " +
            "cpuCores=${Runtime.getRuntime().availableProcessors()}, modelSchonGeladen=$modelAlreadyLoaded, " +
            "hatDotprod=${WhisperJni.hasDotprod()}")

        enterPhase("Modell-Laden")
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
        mark("Modell-Laden")

        enterPhase("Inferenz")
        val text = WhisperJni.nativeTranscribe(
            ctxPtr, samples, language.trim(), threads, WhisperPrompts.contextPrompt(language)
        ) ?: throw IllegalStateException("Native Transkription fehlgeschlagen")
        mark("Inferenz")
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
