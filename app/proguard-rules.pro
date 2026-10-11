# ANDROID-REL-03: app-specific R8 rules for the release build.
#
# Keep this file minimal. R8 runs in full mode (the AGP 9 default), and the
# libraries this app uses ship their own consumer rules, which AGP merges in:
#   - OkHttp / Okio (WebSocket transport)
#   - CameraX (camera-core, camera2, lifecycle, view; includes the JNI classes
#     for libimage_processing_util_jni.so and libsurface_util_jni.so)
#   - Compose, AndroidX Startup and ProfileInstaller
#   - ZXing core is pure Java and uses no reflection
# Nothing in the app uses reflection, Java serialization or code generated at
# runtime, and the Android Keystore is reached through the platform provider by
# name ("AndroidKeyStore"), so no app class has to be kept for it.
#
# What the app does depend on is below, with the reason next to each rule.

# Readable stack traces for a sideloaded build: keep file names and line numbers
# and hide the real source file name. The release workflow uploads the matching
# mapping.txt so a trace can be de-obfuscated with retrace.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# DiagnosticsJournal records `error=<exception simple name>` for a failed
# WebSocket (OkHttpRelaySessionClient). Obfuscating exception class names would
# turn that evidence into meaningless letters for OkHttp's own exception types,
# so keep the names (R8 may still shrink unused ones).
-keepnames class * extends java.lang.Throwable
