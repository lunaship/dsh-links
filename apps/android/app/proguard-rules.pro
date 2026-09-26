# DSH Links release 混淆规则
# R8 仅在 release 构建生效；debug 不受影响。

# Prism4j（代码语法高亮，纯 Kotlin/Android，grammar 经 Prism4jGrammarLocator 加载）
-keep class io.noties.prism4j.** { *; }
-dontwarn io.noties.**

# coil（图片加载）
-dontwarn coil.**
-dontwarn okio.**

# 保留源码行号，便于崩溃排查
-keepattributes SourceFile,LineNumberTable

# Relay 走自定义 SSLSocketFactory / HttpURLConnection；R8 裁掉重载会直接闪退（AbstractMethodError）
-keep class dev.dsh.mobile.core.RelaySslSocketFactory { *; }
-keep class dev.dsh.mobile.core.RelayHttpURLConnection { *; }
-keep class dev.dsh.mobile.core.FailoverHttpURLConnection { *; }
-keepclassmembers class * extends javax.net.ssl.SSLSocketFactory { *; }
-keepclassmembers class * extends java.net.HttpURLConnection { *; }

# Release 日志剥离：R8 删除 android.util.Log 调用，避免把 session id / 路径 / URI 写进 logcat。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
