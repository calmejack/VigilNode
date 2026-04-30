# Add project specific ProGuard rules here.
# By default, the flags in this file are applied to all build types.

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Java-WebSocket
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
