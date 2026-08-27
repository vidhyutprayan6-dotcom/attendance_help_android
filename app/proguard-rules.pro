# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class attendance.help.device.** { *; }

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp / WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.java_websocket.**

-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
