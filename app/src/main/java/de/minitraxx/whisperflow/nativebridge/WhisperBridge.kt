package de.minitraxx.whisperflow.nativebridge

/**
 * Laberboombox — native build tooling milestone.
 *
 * This object only proves that the Gradle -> CMake -> NDK pipeline works
 * end-to-end: Kotlin loads a native library built from `app/src/main/cpp/`
 * and calls into it via JNI.
 *
 * It intentionally contains NO whisper.cpp / GGML integration yet. Real
 * on-device Whisper inference is a later, separate milestone.
 */
object WhisperBridge {

    init {
        System.loadLibrary("whisperbridge")
    }

    /**
     * Returns a placeholder version string produced by the native side.
     * Used only to verify the native build path is wired correctly.
     */
    external fun nativeGetVersion(): String
}
