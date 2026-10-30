# Prebuilt native libraries

Anything in `arm64-v8a/` is packaged into the APK and can be `dlopen()`ed by the
engine.

Drop a build of **openal-soft** for `arm64-v8a` here as `libopenal.so` to get
sound. The engine loads it at runtime (`oalsound.cpp` -> `dlopen("libopenal.so")`);
without it the engine starts fine but without audio.
