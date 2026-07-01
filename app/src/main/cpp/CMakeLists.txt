# Laberboombox native module — build tooling milestone.
#
# This is intentionally minimal: it only proves that the Gradle -> CMake ->
# NDK toolchain pipeline works end-to-end (local build + CI). It contains a
# placeholder implementation, NOT whisper.cpp. Real whisper.cpp sources will
# be added to this module in a later, separate step.

cmake_minimum_required(VERSION 3.22.1)

project("whisperbridge")

add_library(
        whisperbridge
        SHARED
        native-bridge.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        whisperbridge
        ${log-lib}
)
