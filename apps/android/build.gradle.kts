// AGP 9 built-in Kotlin 内置 KGP 2.2.10；此处显式提升到与 Compose 编译器插件一致的版本。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.screenshot) apply false
}
