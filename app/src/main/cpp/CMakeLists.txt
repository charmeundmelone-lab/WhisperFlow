cmake_minimum_required(VERSION 3.22)
project(whisperflow_jni C CXX)

include(FetchContent)

# whisper.cpp wird beim Configure aus dem offiziellen Repo geladen (gepinnter Release-Tag).
# Kein Code-Vendoring im Repo noetig; CI und lokale Builds laden identische Quellen.
set(WHISPER_CPP_TAG "v1.9.1")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper_cpp
    URL "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/${WHISPER_CPP_TAG}.tar.gz"
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(whisperflow_jni SHARED whisper_jni.cpp)

target_link_libraries(whisperflow_jni PRIVATE whisper log)

# Android 15+: 16-KB-Page-Alignment fuer native Libraries
target_link_options(whisperflow_jni PRIVATE "-Wl,-z,max-page-size=16384")
