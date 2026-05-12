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
    implementation("com.google.android.material:material:1.12.0")

    // 核心：只需 Dexlib2
    
    // ❌ 删掉 bundletool，它包含了会导致 R8 崩溃的 dex 测试文件！
    // implementation("com.android.tools.build:bundletool:1.15.6") 
    
    // ❌ 这些也可以删掉，除非你后续真的打算在手机端做 v2/v3 签名和 XML 二进制修改
    // implementation("com.android.tools.build:apksig:8.2.0")
    // implementation("com.googlecode.plist:dd-plist:1.27")
    // implementation("org.ow2.asm:asm:9.6")
    // implementation("org.ow2.asm:asm-commons:9.6")

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))

    // 核心 UI Bundle
    implementation(libs.bundles.compose.ui.core)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.icons.core)

    // AndroidX 基础设施
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // implementation(libs.androidx.webkit)

    // ❌ 删掉 Media3 和 网络库，这个注入器用不到它们，它们会极大增加体积
     implementation(libs.bundles.media3.full)
     implementation(libs.bundles.ktor.full)
     implementation(libs.okhttp)
     implementation(libs.gson)
     implementation(libs.coil.compose)
     implementation(libs.coil.network.okhttp)

    // 实用工具
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.slf4j.nop) // dexlib2 需要的日志空壳

    // 调试预览
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}