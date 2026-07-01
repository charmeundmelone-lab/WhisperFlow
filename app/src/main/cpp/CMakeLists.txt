# Laberboombox native module.
#
# Milestone 1 (done): proved the Gradle -> CMake -> NDK toolchain pipeline
# works end-to-end with a placeholder JNI bridge (native-bridge.cpp).
#
# Milestone 2 (this file): vendor the real whisper.cpp / GGML inference
# engine (MIT-licensed, https://github.com/ggml-org/whisper.cpp) as a
# sub-project and link our JNI bridge against it. whisper.cpp's own
# CMakeLists.txt is designed to be consumed via add_subdirectory() - it
# auto-detects this (WHISPER_STANDALONE / GGML_STANDALONE become OFF) and
# skips anything that would require the parts of its source tree we did not
# vendor (JS bindings, tests, examples, unused GPU backends).
#
# See app/src/main/cpp/whisper.cpp/VENDORING.md for exactly what was vendored,
# what was pruned, and from which upstream tag/commit.

cmake_minimum_required(VERSION 3.22.1)

project("whisperbridge")

# --- whisper.cpp / GGML (vendored, MIT license) -----------------------------
#
# We only need the inference library, not whisper.cpp's own CLI tools,
# examples, tests or JS bindings - those are unconditionally skipped because
# WHISPER_STANDALONE is only ON when whisper.cpp is the top-level CMake
# project (it is not, here).
#
# CPU-only build: GPU backends (CUDA/Vulkan/Metal/SYCL/...) default to OFF
# upstream on non-Apple platforms and their source directories were pruned
# from the vendored copy entirely (see VENDORING.md), so there is nothing to
# disable explicitly.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "whisper.cpp: build static libs, we expose one combined .so" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "whisper.cpp: skip tests" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "whisper.cpp: skip examples" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "whisper.cpp: skip server example" FORCE)

add_subdirectory(whisper.cpp)

# --- Our own JNI bridge ------------------------------------------------------

add_library(
        whisperbridge
        SHARED
        native-bridge.cpp
        whisper-jni.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        whisperbridge
        whisper
        ${log-lib}
)
