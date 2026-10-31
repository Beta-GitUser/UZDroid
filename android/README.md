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
| **"Add file to arguments"** | Opens a document picker filtered to `*/*` - any file, not just WADs, and no extension is filtered anywhere. What the file *is* decides what happens: a text file whose lines are arguments (`-file x.pk3`, `+map map01`, `#`/`;`/`//` for comments) is inlined as a launch config, anything else is appended as `-file <path>`. Files without a real path are copied into `mods/` first. |
| **Engine log** | The native layer mirrors stdout/stderr into logcat *and* `logs/uzdoom.log`. "Share engine log" sends it anywhere. |
| **Reset configuration** | Deletes `config/uzdoom/uzdoom.ini` so the engine starts from defaults again. |

## Touch controls

The engine takes the raw touches (`touch_enable`, on by default on Android); SDL's
own touch-to-mouse translation is switched off so nothing arrives twice.

| Where | What it does |
|---|---|
| Menus, console | **Trackpad.** Drag moves a pointer relative to where it is; a short tap clicks where the pointer points. |
| Gameplay | **Look** anywhere on the right, **move** in the left strip (`touch_movezone`, default 35% wide). The stick presses the movement keys: up/down for forward and back, left/right for turn (rebind them in Controls for strafing). |

Look motion goes through `PostMouseMove()`, so *Options -> Mouse Options*
sensitivity and the invert settings apply on top of `touch_sensitivity`.
There is no tap-to-fire and no gesture for anything else: **all other actions
are buttons**.

### Buttons

Buttons come from `touch-buttons.txt` in the data folder (`Documents/UZDroid`).
**Nothing is preset** - the file is created with an explanation only, and an
empty file means no buttons.

```
label   input   x   y   [width] [height] [alpha]

fire    mouse1   0.86  0.60  0.12  0.18  0.45
use     e        0.74  0.60  0.10  0.14  0.45
jump    space    0.74  0.78  0.10  0.14  0.45
menu    esc      0.94  0.04  0.08  0.10  0.45
```

`x`, `y`, `width` and `height` are fractions of the screen, measured from the
top left. Inputs are `mouse1`..`mouse3` or a key name (`a-z`, `0-9`, `space`,
`enter`, `esc`, `tab`, `shift`, `ctrl`, `alt`, `up`, `down`, `left`, `right`,
`f1`-`f12`, `comma`, `period`, `minus`, `equals`, `lbracket`, `rbracket`,
`backspace`, `pageup`, `pagedown`, `home`, `end`).

The buttons inject ordinary SDL key and mouse button events, so they are bound
in *Options -> Customize Controls* like any real device. The file is re-read
every time the game comes to the foreground, so edits apply without
reinstalling.

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
builds `zipdir` plus the PK3 targets and copies `uzdoom.pk3` (16 MB),
`game_support.pk3`, `lights.pk3`, `brightmaps.pk3`,
`game_widescreen_gfx.pk3`, the soundfont and the FM banks into
`android/app/src/main/assets/` - about 25 MB of APK contents. It takes well
under a minute: the host-assets build only compiles `miniz`, `bzip2`, `lzma`
and the three host tools, not the engine. Gradle runs it automatically if the
assets are missing.

### 3. Build the APK

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and hit Run.

Only `arm64-v8a` is built - the engine is 64-bit only.

### Installing the APK built by CI

Every push to `trunk` or to a work branch builds an APK; the run is at
*Actions -> Android APK*, and the file is the artifact `uzdroid-apk-<run number>`
(`app-release.apk`, ~37 MB). Download it, then either copy it to the device and
open it (allow "install unknown apps" for the browser/file manager), or:

```bash
adb install -r app-release.apk
```

The run overview itself reports what was built (`::notice::` lines): APK size,
the number of `arm64-v8a` libraries and the signing certificate DN. With the
keystore secrets set it reads `CN=UZDroid, C=IT`; if it says
`CN=Android Debug` the secrets were not picked up.

On the first start the app asks for *All files access* - without it the data
folder falls back to `Android/data/org.uzdoom.uzdroid/files/UZDroid`, which
works but is invisible to most file managers. Then drop an IWAD into
`Documents/UZDroid/iwads/` and pick it in the list.

### 3b. Let GitHub build it

`.github/workflows/android.yml` does all of the above on an `ubuntu-latest`
runner: it fetches SDL2, builds the PK3s, runs a CMake configure smoke test
(which catches build-wiring mistakes in about a minute) and then builds the
APK, uploading it as the artifact `uzdroid-apk-<run number>`. It runs on every
push, and can be started by hand from *Actions -> Android APK -> Run workflow*
(choose `release`, `debug` or `both`).

Release signing is optional. Add these repository secrets to get an APK signed
with your own key:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Without them the release APK is signed with the debug key: it installs
normally, but Android refuses to update an app that was signed with a different
key, so pick one and stay with it. For local signed builds, put the same four
values in `android/keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); it is gitignored.

### 4. Sound

The engine has exactly one audio backend: OpenAL, `dlopen()`ed at runtime.
`fetch-deps.sh` pulls [openal-soft](https://github.com/kcat/openal-soft) and
`build-openal.sh` builds it for `arm64-v8a` into
`android/app/src/main/jniLibs/arm64-v8a/libopenal.so`, so the APK has sound out
of the box. If that step fails the build still succeeds and the engine runs
silently - it says so in its log ("Sound init failed. Using nosound.").

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
* `gameconfigfile.cpp` defaults its search path to `$PROGDIR` plus `$PROGDIR/..`,
  `../iwads` and `../mods` instead of the FHS locations, and `I_DetectOS()`
  skips `/etc/os-release` (Android has none) and reports the kernel instead.

## Known gaps

* No touch controls, no on-screen keyboard, no gyro.
* The phone's accelerometer arrives through SDL as a three-axis joystick
  ("Android Accelerometer"), which reads as a permanently held direction. That
  hint is off and sensor-named devices are skipped, so tilt input does nothing
  - which is what an unanchored axis should do until there is a reason to use
  it.
* The in-engine launcher (`-launcher`) renders through ZWidget/SDL and works with
  a mouse, but its widgets are desktop-sized.
* `MANAGE_EXTERNAL_STORAGE` is required for `Documents/UZDroid`; Google Play does
  not allow it for apps like this one.
* No Vulkan backend, no software renderer, no `.ivf` cutscenes (yet).
* First launch unpacks ~25 MB of assets into `engine/`. Files that already
  exist with a non-zero size are left alone, so this only happens once (or
  after the folder is deleted).
* Audio focus is not handled: other apps keep playing while the game runs. SDL
  pauses/resumes the OpenAL device with the activity lifecycle, which is enough
  to not lose sound on a phone call.
