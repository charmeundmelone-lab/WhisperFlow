# whisper.cpp — vendored source (Milestone 2)

This directory contains a **vendored copy** of [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
(MIT licensed — see `LICENSE` in this directory), the GGML-based inference
engine for OpenAI's Whisper model. It is used for the planned on-device
speech recognition feature (see `CLAUDE.md`, "On-Device Whisper —
Umsetzungsplan").

## Why vendored instead of a git submodule

`git submodule add` was avoided because CI checkout / sandbox environments
for this project cannot be assumed to always have unauthenticated network
access to `github.com` at checkout time. A plain vendored copy needs no
extra checkout step and always builds the exact pinned version below.

## Exact source

- Upstream repo: `https://github.com/ggml-org/whisper.cpp`
- Tag: `v1.9.1`
- Commit: `f049fff95a089aa9969deb009cdd4892b3e74916`
- Archive used: `https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.1.tar.gz`
- License: MIT (see `LICENSE`, copied verbatim from upstream)
- Authors: see `AUTHORS`, copied verbatim from upstream

To re-vendor a newer version, download that tag's source tarball, then
repeat the copy/prune steps below against the new tree.

## What was copied from upstream

Only what's needed to build the `whisper` static library (CPU backend, ARM64
Android target) via `add_subdirectory()`:

```
CMakeLists.txt   (top-level whisper.cpp build script)
LICENSE
AUTHORS
cmake/           (CMake helper scripts + .pc.in / -config.cmake.in templates)
include/         (public headers: whisper.h, parakeet.h)
src/             (whisper.cpp, whisper-arch.h, parakeet.cpp, parakeet-arch.h, src/CMakeLists.txt)
ggml/            (the GGML tensor library: CMakeLists.txt, cmake/, include/, src/)
```

**Not copied** (not needed for this build, keeps the vendored tree small):
`examples/`, `tests/`, `models/`, `bindings/`, `samples/`, `grammars/`,
`scripts/`, top-level `README*.md`, `Package.swift`, `AGENTS.md`,
`CONTRIBUTING.md`, `CMakePresets.json`, `build-xcframework.sh`, `ci/`,
`close-issue.yml`.

None of these are referenced by the CMake configure path we use
(`add_subdirectory(whisper.cpp)` from a parent project, i.e.
`WHISPER_STANDALONE` / `GGML_STANDALONE` are `OFF`) — upstream's own
`CMakeLists.txt` only touches them when built standalone or when
`WHISPER_BUILD_TESTS` / `WHISPER_BUILD_EXAMPLES` are explicitly turned on
(we force those `OFF` in `app/src/main/cpp/CMakeLists.txt`).

## What was pruned from the copied directories

Inside `ggml/src/`, the following GPU/accelerator backend subdirectories
were deleted after copying:

```
ggml-cuda, ggml-vulkan, ggml-sycl, ggml-metal, ggml-hip, ggml-cann,
ggml-opencl, ggml-hexagon, ggml-musa, ggml-openvino, ggml-webgpu,
ggml-zdnn, ggml-zendnn, ggml-virtgpu, ggml-rpc, ggml-blas
```

This is safe: every one of these backends is gated behind a CMake option
(`GGML_CUDA`, `GGML_VULKAN`, `GGML_METAL`, ... — see `ggml/CMakeLists.txt`)
that **defaults to `OFF` on non-Apple platforms**, and `ggml/src/CMakeLists.txt`'s
`ggml_add_backend()` function only calls `add_subdirectory(ggml-<backend>)`
when the corresponding option is truthy. With all of them off (Android/CPU
build), none of those directories are ever referenced by the build graph.
Pruning them just keeps the vendored tree ~3x smaller (20 MB -> ~6 MB) with
zero effect on the build.

Also removed: `src/coreml/` and `src/openvino/` (Apple CoreML / Intel
OpenVINO integration — gated by `WHISPER_COREML` / `WHISPER_OPENVINO`,
both default `OFF`, irrelevant on Android).

Kept as-is: `ggml/src/ggml-cpu/` (the CPU backend we actually use) and
`src/parakeet.cpp` / `include/parakeet.h` (a second, separate model
architecture bundled by upstream in the same `src/CMakeLists.txt` —
harmless to build alongside `whisper`, not referenced by our JNI bridge).

## Verification performed for this milestone

No Android NDK/SDK is installed in the sandbox this vendoring step was done
in, so a full `gradle assembleDebug` for arm64-v8a could not be run here
(CI installs the NDK fresh on every run — see `.github/workflows/`). As a
substitute sanity check, this exact pruned tree was CMake-configured and
compiled **standalone on the host (x86_64 Linux, CPU backend only)** via
`add_subdirectory()` from a throwaway parent `CMakeLists.txt` mirroring how
`app/src/main/cpp/CMakeLists.txt` includes it. Result: clean configure, and
`libggml-base.a` / `libggml-cpu.a` / `libggml.a` / `libwhisper.a` all built
successfully with no errors. This confirms the pruned source tree and
directory layout are structurally correct; it does not by itself prove the
Android NDK cross-compile succeeds (different compiler flags, `ANDROID_ABI`
selection in `ggml/src/CMakeLists.txt`'s `arch`-specific code, etc.), which
still needs to be confirmed by CI / a real Android build.

## Next steps (later milestones, not part of this change)

- CI build verification with the actual NDK (arm64-v8a) — first real Android
  build of this tree.
- Model download flow, chunk-buffer logic, feature flag, cloud fallback
  wiring — see `CLAUDE.md` checklist. `OnDeviceWhisperEngine.kt` in
  `app/src/main/java/de/minitraxx/whisperflow/whisper/` is intentionally
  unreferenced by the rest of the app until that work lands.
