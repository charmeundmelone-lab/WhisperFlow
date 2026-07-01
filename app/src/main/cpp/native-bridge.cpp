// Laberboombox — native build tooling milestone.
//
// Placeholder JNI bridge. Purpose: prove that Gradle + CMake + NDK produce a
// loadable .so and that a Kotlin `external fun` call reaches real C++ code.
//
// This file intentionally contains NO whisper.cpp / GGML code. That will be
// added in a later, separate milestone once this tooling path is confirmed
// working in local builds and CI.

#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_de_minitraxx_whisperflow_nativebridge_WhisperBridge_nativeGetVersion(
        JNIEnv *env,
        jobject /* this */) {
    std::string version = "whisperbridge-tooling-0.1-placeholder";
    return env->NewStringUTF(version.c_str());
}
