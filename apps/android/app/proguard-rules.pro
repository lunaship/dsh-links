# DSH Links release 混淆规则
# R8 仅在 release 构建生效；debug 不受影响。

# Prism4j（代码语法高亮，纯 Kotlin/Android，grammar 经 Prism4jGrammarLocator 加载）
-keep class io.noties.prism4j.** { *; }
-dontwarn io.noties.**

# jlatexmath（LaTeX 原生绘制，纯 Java 库）
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn ru.noties.jlatexmath.**

# coil（图片加载）
-dontwarn coil.**
-dontwarn okio.**

# 保留源码行号，便于崩溃排查
-keepattributes SourceFile,LineNumberTable
