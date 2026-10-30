# UZDroid for Android

An Android wrapper for UZDoom: the engine is built as `libmain.so`, SDL2's
`SDLActivity` loads it, and a small Java launcher in front of it handles WAD
selection, render resolution, command line arguments and the data folder.

This is iteration 1. There are **no touch controls** yet - a physical mouse and
keyboard (or a gamepad) are assumed. Everything in-game is the stock UZDoom UI.

---

## What the launcher does

| Feature | Notes |
|---|---|
| **Data folder** | `Documents/UZDroid` mirrors a desktop install root: `config/`, `data/` (saves), `logs/`, `screenshots/`, `iwads/`, `mods/`, `engine/` (extracted PK3s). |
| **WAD list** | Scans `iwads/` and `data/games/uzdoom/` for `*.wad`; tapping one starts the engine with `-iwad`. |
| **Render scale** | 25-200 % of the screen. Anything other than "native" is applied as `+vid_scalemode 5` with computed `vid_scale_customwidth/height`. |
| **Custom resolution** | Absolute width x height, or one tap to copy the current screen size. |
| **Launch arguments** | Free-form text field, appended after the generated arguments. The field shows exactly what the engine's own parser will see (space separated, double quotes honoured). |
| **"Add file to arguments"** | Opens a document picker filtered to `*/*` - any file, not just WADs. If the file has a real path (normal case for shared storage) that path is appended as-is; otherwise it is copied into `mods/` first and that path is added. |
| **Engine log** | The native layer mirrors stdout/stderr into logcat *and* `logs/uzdoom.log`. "Share engine log" sends it anywhere. |
| **Reset configuration** | Deletes `config/uzdoom/uzdoom.ini` so the engine starts from defaults again. |

The data folder is used through the engine's normal XDG variables, set right
before the engine starts:

```
HOME             = <root>
XDG_CONFIG_HOME  = <root>/config      -> <root>/config/uzdoom/uzdoom.ini
XDG_DATA_HOME    = <root>/data        -> <root>/data/games/uzdoom (saves, autoloads)
XDG_CACHE_HOME   = <root>/cache
XDG_PICTURES_DIR = <root>/screenshots
UZDOOM_PROGDIR   = <root>/engine      (the PK3s, unpacked from the APK on first run)
UZDOOM_LOG       = <root>/logs/uzdoom.log
```

`Documents/UZDroid` requires **All files access**, because the engine can only
work with real POSIX paths. Grant it on the first screen; without it the app
falls back to its private folder, which works but is not visible in a file
manager.

---

## Building

### Requirements

* Android Studio (or just the Android SDK + NDK r26/r27 and JDK 17)
* CMake 3.22+ (the SDK ships one)
* A host build environment for the PK3 step: CMake, Python 3, and - on Linux -
  the usual GCC/Clang setup. This does *not* need SDL2 or any other engine
  dependency, only the host tools bundled in the repo.

### 1. Fetch SDL2

```bash
./android/scripts/fetch-deps.sh          # puts SDL2 in android/deps/SDL
```

(Or point `uzdoom.sdl2Dir` in `android/gradle.properties` at an existing SDL2
source tree.)

### 2. Generate the engine data

```bash
./android/scripts/build-host-assets.sh
```

This does a host-native CMake configure with `-DUZDOOM_HOST_ASSETS_ONLY=ON`,
builds `zipdir` plus the PK3 targets and copies `uzdoom.pk3`,
`game_support.pk3`, `lights.pk3`, `brightmaps.pk3`,
`game_widescreen_gfx.pk3`, the soundfont and the FM banks into
`android/app/src/main/assets/`. Gradle runs it automatically if the assets are
missing.

### 3. Build the APK

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and hit Run.

Only `arm64-v8a` is built - the engine is 64-bit only.

### 4. Sound (optional)

The engine has exactly one audio backend: OpenAL, `dlopen()`ed at runtime. Build
[openal-soft](https://github.com/kcat/openal-soft) for `arm64-v8a` and drop
`libopenal.so` into `android/app/src/main/jniLibs/arm64-v8a/`. Without it the
engine runs silently but otherwise fine.

---

## How the Android build differs from the desktop one

Handled by `-DANDROID` in the engine's CMake, no separate build tree:

* `zdoom` becomes a **shared library** `libmain.so` (`add_library(... SHARED)`),
  because SDLActivity `dlopen`s it and calls `SDL_main()`.
* SDL2 is built from source as part of the build (`-DUZDOOM_SDL2_SOURCE_DIR`),
  as a shared `libSDL2.so`.
* Host tooling (`tools/`, `wadsrc*`) is skipped; the PK3s come from
  `build-host-assets.sh`.
* The crash handler (`crashcatcher.c`, which shells out to gdb) is excluded and
  replaced with logcat + file logging (`src/common/platform/android`).
* The software renderer is off (SSE2; its scalar fallback is unvalidated on ARM).
* Vulkan is off for now (`-DUZDOOM_ANDROID_VULKAN=ON` to try it). The GLES
  renderer is the default backend on Android and `gl_es` defaults to true.
* libvpx is optional (`NO_LIBVPX`): without it, `.ivf` cutscenes report an error
  instead of playing. Nothing else changes.

## Known gaps

* No touch controls, no on-screen keyboard, no gyro.
* The in-engine launcher (`-launcher`) renders through ZWidget/SDL and works with
  a mouse, but its widgets are desktop-sized.
* `MANAGE_EXTERNAL_STORAGE` is required for `Documents/UZDroid`; Google Play does
  not allow it for apps like this one.
* No Vulkan backend, no software renderer, no `.ivf` cutscenes (yet).
* First launch unpacks ~20-40 MB of assets into `engine/`.
