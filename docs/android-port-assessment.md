# Can UZDroid be ported to Android with a wrapper-launcher?

**Short answer: yes — and this tree is unusually well prepared for it, because the three hardest
pieces are already here.** But a *thin* wrapper (APK that just starts the engine and gets out of
the way) is not enough. The engine half is maybe 20% of the work; the mobile UX layer
(touch controls, storage, lifecycle, data shipping) is the other 80%.

Everything below is verified against this checkout (branch `arena/3d87883f-uzdroid`, commit
`3c6fad6`), with file:line references.

> **Status update:** the scaffolding described in sections 4-5 now exists in
> [`android/`](../android/README.md) together with the engine-side `-DANDROID`
> CMake path. The launcher implements the WAD list, render scale / custom
> resolution, an editable command line with a file picker, log sharing and the
> `Documents/UZDroid` data root described below. Touch controls are still
> deliberately out of scope for this iteration.

---

## 1. What is already in the tree

| Piece | Where | Notes |
|---|---|---|
| **SDL2 platform layer** | `src/common/platform/posix/sdl/` (9 files, ~3.4k LOC) | `i_main`, `i_system`, `hardware`, `i_input`, `i_joystick`, `sdlglvideo`, `st_start`. This is the only non-Windows/non-macOS backend, so Android reuses it wholesale. Requires SDL ≥ 2.0.6 (`sdlglvideo.cpp:331`). |
| **GLES renderer** | `src/common/rendering/gles/` (16 files + glad) | `HAVE_GLES2` defaults **ON** for non-Apple (`CMakeLists.txt:306-310`). Selected automatically unless `vid_preferbackend == BACKEND_OPENGL` (`sdlglvideo.cpp:419`). This is the same backend Delta Touch and mobilerpgpack use. |
| **ES context request** | `sdlglvideo.cpp:203-208` | There is already a `gl_es` cvar (line 82) that sets `SDL_GL_CONTEXT_PROFILE_ES` + 2.0 — i.e. the SDL context creation path for ES exists today. |
| **Vulkan (optional)** | `src/common/rendering/vulkan/`, `libraries/ZVulkan` | Goes through `SDL_Vulkan_CreateSurface` (`sdlglvideo.cpp:313`), which maps to `VK_KHR_android_surface` on Android. Plausible on modern devices. |
| **ARM64 awareness** | `cmake/TargetArch.cmake`, `src/CMakeLists.txt:219-222,394` | aarch64 detection works while cross-compiling (preprocessor-based, no `uname`). `NO_SSE` scalar fallbacks for the software renderer. JIT/asmjit is x86_64-only (`CMakeLists.txt:297-299`), so ARM just runs the interpreter. |
| **aarch64 runtime path** | `src/common/platform/posix/sdl/i_system.cpp:158-166` | CPU-speed timer already reads `cntfrq_el0` on aarch64. Somebody thought about ARM before. |
| **Launcher UI** | `src/widgets/` (`launcherwindow.cpp`, `playgamepage`, `settingspage`, `networkpage`, `aboutpage`, `errorwindow`) + `libraries/ZWidget` | ZWidget has a **working SDL2 display backend** (`libraries/ZWidget/src/window/sdl2/`) and `GameMain()` falls back to it on non-Windows (`d_main.cpp:4374-4378`). The launcher is already reached on Linux via `I_PickIWad` → `LauncherWindow::ExecModal` (`posix/sdl/i_system.cpp:353`). |
| **Dependency diet** | `CMakeLists.txt`, `libraries/ZMusic` | Everything heavy is bundled: miniz (zlib substitute), bzip2, lzma, libwebp, cppdap, abseil, ZMusic + dumb/game-music-emu/ADL/OPN. OpenAL and libsndfile are `dlopen`ed and optional (`DYN_OPENAL`, `DYN_SNDFILE` default ON). **No zlib, no libcurl (updater only), no asmjit on ARM.** Required externals: SDL2, libvpx, and Python3 on the host at build time. |

**Prior art proves the target is real:** Delta Touch ships "UZDoom (dev)" on Google Play, and
`sandstranger/com.mobilerpgpack.phone` ships an Android UZDoom build on top of beloko's GLES
renderer (the line that ended up in this tree). `emileb/gzdoom` is the older reference port.

---

## 2. What is actually missing

Nothing here is engine surgery — it is glue, packaging, and UX.

| # | Gap | Effort | Detail |
|---|---|---|---|
| 1 | **Android build scaffolding** | 2–5 d | No `android/`, no Gradle files, no `AndroidManifest.xml`, no JNI. CMake builds `zdoom` as an **executable**; Android needs `libmain.so` + an `SDLActivity` subclass. `main()` in `i_main.cpp:181` will already be renamed to `SDL_main` by `SDL_main.h` (pulled in via `<SDL2/SDL.h>`), so the entry point mostly "just works". |
| 2 | **Cross-compiled deps for arm64-v8a** | 1–3 d | SDL2 (build from source), libvpx, optionally openal-soft, libsndfile/mpg123. Toolchain: `$NDK/build/cmake/android.toolchain.cmake`, `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24`. Mostly yak-shaving, no design decisions. |
| 3 | **Paths / progdir** | 1–2 d | `posix/unix/i_specialpaths.cpp` only understands `$XDG_*`/`$HOME`. Cheapest fix: `setenv("HOME"/"XDG_CONFIG_HOME"…)` from JNI before `GameMain()` pointing at the app-private dir → zero engine changes. `progdir` comes from `realpath(argv[0])` (`i_main.cpp:223`) which is meaningless on Android → use the existing `-DPROGDIR=...` hook (`src/CMakeLists.txt:455`). |
| 4 | **Shipping the PK3 data** | 1–2 d | `uzdoom.pk3`, `game_support.pk3`, `lights.pk3`, `brightmaps.pk3`, `game_widescreen_gfx.pk3` are generated at build time by the **host** `zipdir` tool (`CMakeLists.txt:121-170`, `wadsrc*/CMakeLists.txt`). Needs a host pre-build step, then copy into `assets/` or unpack into `filesDir` on first run. |
| 5 | **Touch controls** | 1–3 weeks | **There is literally zero touch code in the repo** — grep for `SDL_FINGER`, `SDL_TouchID`, `onTouchEvent` returns nothing. Input is keyboard/mouse/SDL_GameController only. You get free tap→mouse click from SDL's default `SDL_HINT_TOUCH_MOUSE_EVENTS`, but look control, strafing, weapon switching, automap, and the keyboard-driven menus all need a virtual gamepad. Two options: Java overlay in the activity injecting synthetic events, or engine-side on-screen controls (what Delta Touch does; its touch layer is GPL and studyable). |
| 6 | **Storage access** | ~1 week | Scoped storage. The engine's filesystem is POSIX-path based and cannot read `content://` URIs. You need a SAF picker that imports WADs into the app dir, or `MANAGE_EXTERNAL_STORAGE` (which limits Play Store distribution). |
| 7 | **Audio** | 2–4 d | The engine has exactly one sound backend: OpenAL (`oalsound.cpp`, `dlopen("libopenal.so")` at line 75). Ship openal-soft built as `libopenal.so` inside the APK — `dlopen` by soname resolves in `nativeLibraryDir` automatically. Also handle audio suspend on background. |
| 8 | **Crash handler + restart** | ~0.5 d | `crashcatcher.c` `fork()`s and shells out to **gdb** (lines 136-186, 246); useless on Android — compile it out and use logcat/NDK crash dumps. `Linux_I_TryRestart` uses `fexecve("/proc/self/exe")` (`i_main.cpp:64-78`) — must be `#ifdef`ed out for Android. |
| 9 | **Lifecycle** | 2–3 d | SDLActivity handles pause/resume, but the engine assumes it keeps running forever. Needs `SDL_APP_WILLENTERBACKGROUND`/`FOREGROUND` handling, config save on pause, timer/frame-pacing fixes. |
| 10 | **CI / signing / store packaging** | 2–4 d | Nothing exists; `.github/workflows` only builds desktop. |

---

## 3. Gotchas that will bite

- **The GLES backend is an ES 2.0-class path, not ES 3.2.** It compiles GLSL ES `100` shaders
  (`gles_system.cpp:222`), and `sdlglvideo.cpp:206` hardcodes context major/minor `2/0`. Popular
  mods (e.g. Voxel Doom II) need GLES 3.2 + user shaders on mobile — that means extending the
  context creation and shader versions, not just flipping a flag.
- **`NO_SSE` is effectively untested on ARM.** The condition at `src/CMakeLists.txt:219` keys off
  `CAN_DO_MFPMATH`, a variable that is **defined nowhere in the repo**. It happens to evaluate
  correctly (`NOT X64` → true), but the scalar software-renderer path it switches to has
  presumably not been exercised. Expect fixups if you keep the software renderer.
- **64-bit only.** `src/CMakeLists.txt:36`: *"Right now only 64 bit is supported."* → `arm64-v8a`
  only, no 32-bit `armeabi-v7a`, which cuts off a slice of old devices.
- **Build weight.** ~624k lines of non-thirdparty C++ plus bundled libs. First full Android build
  is slow; LTO is on by default (`CMakeLists.txt:42-53`) — set `-DFORCE_NO_LTO=ON` while iterating.
- **The launcher is mouse-driven.** It renders fine over SDL, but every widget is hover/click
  based; it needs a touch pass (bigger hit targets, no hover) before it is usable on a phone.
- **GPLv3.** An APK is a derivative work: source must be published, and no id Software WAD data
  may ever be bundled.

---

## 4. Two "wrapper" flavours

**A. True thin wrapper** — APK that starts a bundled native binary and otherwise stays out of the
way (the Termux/proot style). Technically possible for an arm64 Linux ELF, but you lose EGL,
touch, audio focus and lifecycle plumbing, performance suffers, and it is not shippable. Not
recommended.

**B. Native library + Java shell (recommended)** — build the engine as `libmain.so` behind
`SDLActivity`, keep all the engine's own systems (including its in-tree launcher UI), and write the
shell in Kotlin: WAD import, touch overlay, settings, controller mapping.

```
android/
  app/            # Gradle, Kotlin, SDLActivity subclass, touch overlay
  deps/           # prebuilt arm64 SDL2, libvpx, openal-soft
  build/          # host-built pk3s → assets/
src/common/platform/posix/sdl/   # + #ifdef __ANDROID__ branches
CMakeLists.txt                   # + a -DANDROID path: SHARED lib target, skip updater/zipdir
```

---

## 5. Recommended path and rough timeline

| Phase | Work | Time |
|---|---|---|
| 0 | Toolchain smoke test: build the stock **SDL2 Android sample**, then link the engine as `libmain.so` and get *any* output in logcat | 1–2 d |
| 1 | Engine boots: PROGDIR + XDG env fixups, pk3s in assets, `--cc`/restart/updater code compiled out, OpenAL in the APK | ~1 week |
| 2 | Renders and is playable with a Bluetooth pad: GLES backend, frame pacing, lifecycle | 1–2 weeks |
| 3 | Touch controls + touch-friendly launcher pages | 2–4 weeks |
| 4 | Storage (SAF import), per-game configs, mod manager, crash reporting, release signing | 3–6 weeks |

**Realistic:** a rough playable arm64 build in **3–6 weeks** of focused work; something comparable
to Delta Touch in **2–4 months**. The good news is that most of that is Kotlin and packaging, not
Doom internals — the parts of this repo that would normally make an Android port terrifying (a
GLES renderer, an SDL platform layer, a cross-platform launcher, an ARM-safe build) are already
done and already shipping on other people's Android ports.
