# gomobile (Xray-core AAR): classes are looked up by name from Go via JNI
-keep class libv2ray.** { *; }
-keep class go.** { *; }

# hev-socks5-tunnel JNI registers these natives by class/method name
-keep class com.pooy.vpn.service.TProxyService { *; }
-keepclasseswithmembernames class * { native <methods>; }

-dontwarn go.**
