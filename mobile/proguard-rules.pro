# Keep JNI bridge methods/classes used by the upstream Chiaki native library.
-keep class com.metallic.chiaki.lib.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
