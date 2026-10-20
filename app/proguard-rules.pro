# Minification is off in Phase 0. These rules are kept so that turning it on later does not
# break Room or Media3.
-keepattributes *Annotation*
-keep class app.lumen.player.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
