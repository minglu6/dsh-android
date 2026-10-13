# kotlinx.serialization (JSON helpers used by the protocol client)
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class kotlinx.serialization.json.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
    *** Companion;
}

# OkHttp / Okio (WebSocket transport)
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }

# Kotlin coroutines (continuation machinery)
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# VT100 terminal emulator + Compose draw path must stay intact under R8.
-keep class dev.dsh.pocket.app.AnsiTerminal { *; }
-keep class dev.dsh.pocket.app.TermCell { *; }
-keep class dev.dsh.pocket.app.TokyoNight { *; }
-keep class dev.dsh.pocket.app.TerminalEmulatorKt { *; }

# ML Kit barcode (QR pairing) — reflection-selected detectors
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
