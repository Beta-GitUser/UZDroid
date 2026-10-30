# R8/ProGuard rules. Release builds currently run with minifyEnabled = false,
# so this file is inert; it is here so that turning minification on does not
# break the JNI boundary.

# SDL reaches every one of its Java classes from native code, and the engine's
# entry point (SDL_main in libmain.so) is looked up by name.
-keep class org.libsdl.app.** { *; }
-keep class org.uzdoom.uzdroid.** { *; }

# Native methods are bound by name at class load time.
-keepclasseswithmembernames class * {
    native <methods>;
}

# FileProvider is instantiated from the manifest, and its authority helper is
# only ever used reflectively.
-keep class androidx.core.content.FileProvider { *; }

-dontwarn org.libsdl.app.**
-dontwarn org.uzdoom.uzdroid.**
