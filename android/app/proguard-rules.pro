# Hoot ProGuard Rules for Release Optimization

# Keep Hoot model data classes
-keep class app.hoot.model.** { *; }

# Keep conversion engines
-keep class app.hoot.engine.** { *; }

# Keep AndroidX & Compose rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontwarn androidx.**

# Open-Source Engines
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.caverock.androidsvg.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.caverock.androidsvg.** { *; }
