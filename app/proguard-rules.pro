# Keep Hilt / WebRTC / OkHttp symbols for later phases.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn org.bouncycastle.**
