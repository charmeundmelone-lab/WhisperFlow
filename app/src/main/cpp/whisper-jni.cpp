// Laberboombox — on-device Whisper JNI bridge (Milestone 2).
//
// Thin JNI layer over whisper.cpp's C API (see whisper.cpp/include/whisper.h).
// Structurally modeled on the official whisper.cpp Android example
// (examples/whisper.android/lib/src/main/jni/whisper/jni.c in the upstream
// ggml-org/whisper.cpp repo) but written from scratch for our own package /
// naming / error-handling conventions - no code copied from that example.
//
// Design notes:
//  - Every entry point is wrapped in try/catch. whisper.cpp itself is C, so
//    it cannot throw C++ exceptions, but GGML can abort() on some fatal
//    internal errors (e.g. corrupt model file) - that is a hard native
//    crash we cannot intercept from C++ (no signal handling here by design,
//    keep this milestone simple). What we *can* guarantee is that anything
//    that surfaces as a C++ exception (bad_alloc, std::exception from our
//    own code, etc.) is caught here and turned into a clean 0 / null / -1
//    return value instead of propagating into the JVM as a fatal error.
//  - This file is NOT referenced by nativeGetVersion()'s bridge
//    (native-bridge.cpp) or by anything else in the app yet. It only backs
//    OnDeviceWhisperEngine.kt, which is itself not called from anywhere in
//    the existing app code (see class doc comment there). Lazy-loading /
//    zero-impact-on-existing-code principle from CLAUDE.md.

#include <jni.h>
#include <android/log.h>

#include <string>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Exported function names below must match the fully-qualified Kotlin object
// that declares the `external fun`s (JNI name mangling: '.' -> '_'). The
// natives live on the private top-level object `NativeWhisperBridge` in
// OnDeviceWhisperEngine.kt (de.minitraxx.whisperflow.whisper package) - NOT
// on OnDeviceWhisperEngine itself, and NOT on a companion object (which
// would need an extra "00024Companion" infix in the symbol name).

extern "C" JNIEXPORT jlong JNICALL
Java_de_minitraxx_whisperflow_whisper_NativeWhisperBridge_nativeLoadModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPath) {
    if (modelPath == nullptr) {
        LOGE("nativeLoadModel: modelPath is null");
        return 0;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("nativeLoadModel: failed to read modelPath string");
        return 0;
    }

    struct whisper_context *ctx = nullptr;
    try {
        struct whisper_context_params cparams = whisper_context_default_params();
        // GPU stays off on purpose: this build only vendors/compiles the CPU
        // backend (see VENDORING.md). use_gpu defaults to true upstream, so
        // it must be forced off here or context init could try to use a
        // backend we never linked in.
        cparams.use_gpu = false;

        ctx = whisper_init_from_file_with_params(path, cparams);
    } catch (const std::exception &e) {
        LOGE("nativeLoadModel: exception while loading '%s': %s", path, e.what());
        ctx = nullptr;
    } catch (...) {
        LOGE("nativeLoadModel: unknown exception while loading '%s'", path);
        ctx = nullptr;
    }

    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("nativeLoadModel: whisper_init_from_file_with_params returned null");
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_minitraxx_whisperflow_whisper_NativeWhisperBridge_nativeTranscribe(
        JNIEnv *env,
        jobject /* thiz */,
        jlong contextHandle,
        jfloatArray pcmAudio,
        jstring language) {
    if (contextHandle == 0) {
        LOGE("nativeTranscribe: contextHandle is 0 (no model loaded)");
        return nullptr;
    }
    if (pcmAudio == nullptr) {
        LOGE("nativeTranscribe: pcmAudio is null");
        return nullptr;
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextHandle);

    const jsize numSamples = env->GetArrayLength(pcmAudio);
    if (numSamples <= 0) {
        LOGE("nativeTranscribe: pcmAudio has no samples");
        return nullptr;
    }

    jfloat *samples = env->GetFloatArrayElements(pcmAudio, nullptr);
    if (samples == nullptr) {
        LOGE("nativeTranscribe: failed to read pcmAudio elements");
        return nullptr;
    }

    // Optional BCP-47-ish / whisper language code, e.g. "de", "en". Null or
    // empty means auto-detect, matching whisper.cpp's own convention
    // (params.language = "auto").
    std::string languageStr = "auto";
    const char *languageChars = nullptr;
    if (language != nullptr) {
        languageChars = env->GetStringUTFChars(language, nullptr);
        if (languageChars != nullptr && languageChars[0] != '\0') {
            languageStr = languageChars;
        }
    }

    jstring result = nullptr;
    try {
        struct whisper_full_params params =
                whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.single_segment = false;
        params.no_context = true;
        params.language = languageStr.c_str();
        // Conservative default; caller (Kotlin side) may revisit this once
        // real on-device performance numbers exist on the target device
        // (Nothing Phone 3a, Snapdragon 7s Gen 3).
        params.n_threads = 4;

        whisper_reset_timings(ctx);

        const int rc = whisper_full(ctx, params, samples, static_cast<int>(numSamples));
        if (rc != 0) {
            LOGE("nativeTranscribe: whisper_full failed with code %d", rc);
        } else {
            std::string text;
            const int numSegments = whisper_full_n_segments(ctx);
            for (int i = 0; i < numSegments; ++i) {
                const char *segment = whisper_full_get_segment_text(ctx, i);
                if (segment != nullptr) {
                    text += segment;
                }
            }
            result = env->NewStringUTF(text.c_str());
        }
    } catch (const std::exception &e) {
        LOGE("nativeTranscribe: exception: %s", e.what());
        result = nullptr;
    } catch (...) {
        LOGE("nativeTranscribe: unknown exception");
        result = nullptr;
    }

    if (languageChars != nullptr) {
        env->ReleaseStringUTFChars(language, languageChars);
    }
    env->ReleaseFloatArrayElements(pcmAudio, samples, JNI_ABORT);

    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_de_minitraxx_whisperflow_whisper_NativeWhisperBridge_nativeFreeContext(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong contextHandle) {
    if (contextHandle == 0) {
        return;
    }

    try {
        auto *ctx = reinterpret_cast<struct whisper_context *>(contextHandle);
        whisper_free(ctx);
    } catch (const std::exception &e) {
        LOGE("nativeFreeContext: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeFreeContext: unknown exception");
    }
}
