# ProGuard/R8 rules for release builds.
#
# Nothing here had rules before, because nothing has ever shipped a release
# build - the fleet runs assembleDebug, where minification is off. The first
# minified build would have failed in ways that are hard to read: the native
# engine resolves its Java side by name at runtime, so R8 renaming a method
# does not break the build, it breaks the radio, silently, after install.

# ── The JNI bridge ──────────────────────────────────────────────────────────
# The native library looks up com.jvdegithub.aiscatcher.AisCatcherJava and its
# methods by their exact names. Renaming or removing any of them leaves the
# decoder running with nowhere to deliver sentences to. The package name is
# fixed on the native side, which is why it survived the rename to
# com.porttracker.ais - do not "tidy" it.
-keep class com.jvdegithub.aiscatcher.** { *; }
-keepclassmembers class com.jvdegithub.aiscatcher.** {
    *;
}

# Belt and braces: any native method anywhere, plus the classes holding them.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Callbacks arrive from C++ on the native receiver thread. R8 sees no Java
# caller and would treat them as dead code.
-keepclassmembers class * {
    void on*(java.lang.String);
}

# ── Kotlin objects reached only from native code ────────────────────────────
# `object` singletons are instantiated through INSTANCE; R8 can drop the field
# when the only reader is JNI.
-keepclassmembers class ** {
    public static ** INSTANCE;
}

# ── Libraries ───────────────────────────────────────────────────────────────
# Paho reflects over its own classes for the persistence layer, and pulls in
# optional JMS/OSGi pieces that are not on the classpath.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.**

# NanoHTTPD subclasses are resolved reflectively in places.
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# Gson serialises the config and status models by field name. Renamed fields
# would silently change the JSON the web UI and the gateway consume.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# The app's own model/config classes go over the wire as JSON by field name.
-keepclassmembers class com.porttracker.ais.** {
    <fields>;
}

# ── Diagnostics ─────────────────────────────────────────────────────────────
# Keep line numbers so a crash from a station is readable, but hide the
# original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
