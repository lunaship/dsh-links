import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    namespace = "dev.dsh.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.dsh.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.0-beta.1"
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

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val copyLegalAssets by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("LICENSE"))
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/legalAssets/legal"))
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }.configureEach {
    dependsOn(copyLegalAssets)
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
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.jlatexmath.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    // 本地 JVM 单测：org.json 在 android.jar stub 里不可用，需真实实现
    testImplementation("org.json:json:20240303")
}
