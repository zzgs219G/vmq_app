import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()
var hasValidSigning = false

if (keystorePropsFile.exists()) {
    FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    hasValidSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        keystoreProps.containsKey(it)
    }
}

android {
    namespace = "com.xixin.lian"
    compileSdk = libs.versions.compileSdk.get().toInt()
    
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    defaultConfig {
        applicationId = "com.xixin.lian"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 102
        versionName = "1.0.2"

        vectorDrawables { useSupportLibrary = true }
        // 极致瘦身：只保留中文资源
        resConfigs("zh", "zh-rCN")
    }

    signingConfigs {
        if (hasValidSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasValidSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:strongSkipping=true"
        )
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // Compose BOM 和核心 UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui.core)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.icons.core)
implementation("com.google.android.material:material:1.12.0")
    // AndroidX 基础设施
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // 网络库（只用 Ktor 核心 + OkHttp 引擎，代码一行不用改）
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    // 协程与日志
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.slf4j.nop)

    // 调试预览
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}