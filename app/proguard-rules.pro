# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable
# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Firebase component discovery — registrars are named from manifest metadata
# and instantiated reflectively. Preserve their constructors in minified
# release builds so App Check providers register correctly.
# ---------------------------------------------------------------------------
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }

# ---------------------------------------------------------------------------
# kotlinx.serialization
# @Serializable models (data/db/Entities.kt, backup/BackupManifest.kt,
# security/BackupCrypto.kt) rely on generated $serializer + Companion lookups.
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# SQLCipher (net.zetetic:sqlcipher-android) — classes reached via JNI.
# ---------------------------------------------------------------------------
-keep,includedescriptorclasses class net.sqlcipher.** { *; }
-keep,includedescriptorclasses interface net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ---------------------------------------------------------------------------
# LiteRT / TensorFlow Lite — interpreter & ops reached via JNI/reflection.
# ---------------------------------------------------------------------------
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
