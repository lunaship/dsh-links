import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.screenshot)
}

val localSigningEnvFile = file(
    "${System.getProperty("user.home")}/Library/Application Support/DSH Links Signing/env"
)

fun loadLocalSigningEnv(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    val result = mutableMapOf<String, String>()
    file.readLines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val body = line.removePrefix("export ").trim()
        val eq = body.indexOf('=')
        if (eq <= 0) return@forEach
        val key = body.substring(0, eq).trim()
        var value = body.substring(eq + 1).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        result[key] = value
    }
    return result
}

val localSigningEnv = loadLocalSigningEnv(localSigningEnvFile)
fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localSigningEnv[name]?.takeIf { it.isNotBlank() }

val releaseSigningEnv = listOf(
    "DSH_LINKS_KEYSTORE_PATH",
    "DSH_LINKS_KEYSTORE_PASSWORD",
    "DSH_LINKS_KEY_ALIAS",
    "DSH_LINKS_KEY_PASSWORD",
).associateWith { signingValue(it) }
val releaseSigningPresent = releaseSigningEnv.values.count { it != null }
check(releaseSigningPresent == 0 || releaseSigningPresent == 4) {
    val missing = releaseSigningEnv.filterValues { it == null }.keys.joinToString()
    "Incomplete release signing environment; missing: $missing"
}
val releaseSigningReady = releaseSigningPresent == 4
val allowUnsignedRelease =
    providers.gradleProperty("allowUnsignedRelease").orNull == "true"

android {
    namespace = "dev.deeplinks"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.deeplinks"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "0.5.0-beta.20"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("DSH_LINKS_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnv.getValue("DSH_LINKS_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("DSH_LINKS_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("DSH_LINKS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 与签名 release 共存于同一设备（instrumented 测试直接跑 debug 变体，
            // 不必卸载用户手机上的 release 包）。
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Compose Preview Screenshot Testing：启用 screenshotTest 源集
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

val legalFiles = listOf(
    rootProject.file("LICENSE"),
    rootProject.file("THIRD_PARTY_NOTICES.md"),
)

/** 将 LICENSE / 第三方声明复制进 APK assets（Variant API 要求 DirectoryProperty 输出）。 */
abstract class CopyLegalAssets : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.resolve("legal")
        out.mkdirs()
        sources.files.forEach { f ->
            check(f.isFile) { "Missing required legal asset: ${f.path}" }
            f.copyTo(out.resolve(f.name), overwrite = true)
        }
    }
}

val copyLegalAssets = tasks.register<CopyLegalAssets>("copyLegalAssets") {
    sources.from(legalFiles)
    // 任务输出 = assets 根目录，内部再放 legal/ 子目录，保持历史打包路径 assets/legal/。
    outputDir.set(layout.buildDirectory.dir("generated/legalAssets"))
}

// AGP 9：生成的资产目录必须走 Variant API（Provider 不能直接进 srcDir）。
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalAssets) { it.outputDir }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(copyLegalAssets)
}

tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" }.configureEach {
    doFirst {
        check(releaseSigningReady || allowUnsignedRelease) {
            "assembleRelease 需要本机签名材料（环境变量 DSH_LINKS_* 或 ~/Library/Application Support/DSH Links Signing/env）。验证 R8 可用 -PallowUnsignedRelease=true。Debug 构建不使用 Release Key。"
        }
    }
}

tasks.register("ensureReleaseSigning") {
    group = "build"
    description = "Fails unless all four DSH_LINKS_* release signing values are set."
    doFirst {
        check(releaseSigningReady) {
            "Signed release requires DSH_LINKS_KEYSTORE_PATH, DSH_LINKS_KEYSTORE_PASSWORD, DSH_LINKS_KEY_ALIAS, and DSH_LINKS_KEY_PASSWORD"
        }
        val keystore = file(releaseSigningEnv.getValue("DSH_LINKS_KEYSTORE_PATH")!!)
        check(keystore.isFile) { "Release keystore not found: ${keystore.path}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.zxing.embedded)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // 截图测试源集
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling.preview)
    screenshotTestImplementation(libs.screenshot.validation.api)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // 本地 JVM 单测：org.json 在 android.jar stub 里不可用，需真实实现
    testImplementation(libs.org.json)
}
