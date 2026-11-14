import java.util.Properties

plugins {
    // AGP 9+ 内置 Kotlin 支持，不再单独应用 org.jetbrains.kotlin.android
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI 签名注入：根目录 keystore.properties（不入库，由 GitHub Actions 从 Secrets 生成），
// 本地没有该文件时 release 构建自动回退为 debug 签名
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.jimmy.expiryreminder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jimmy.expiryreminder"
        minSdk = 26
        targetSdk = 36
        // versionName 由 release-please 自动维护（generic extra-file 只替换标记之间的版本号）；
        // versionCode 从 versionName 推导（1.2.29 -> 10229），保证每次发版递增
        // x-release-please-start-version
        versionName = "1.9.0"
        // x-release-please-end
        val (major, minor, patch) = versionName!!.split(".").map { it.toInt() }
        versionCode = major * 10000 + minor * 100 + patch

        // ML Kit 的 native 库每个 ABI 约 7–11MB，四个 ABI 全打进去光 .so 就 41MB。
        // x86/x86_64 只用于模拟器，真机不需要，裁掉后 APK 减少约 23MB。
        // 注意：这样打的包在 x86 模拟器上装不了，需要调试时临时把 ABI 加回来。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        // debug 包名加后缀，与正式版共存：手机上装着 1.3.0 正式版时，
        // versionCode 更低的 debug 包不会被系统的降级保护拦截，也不必卸载正式版
        getByName("debug") {
            applicationIdSuffix = ".debug"
            // 桌面图标名带后缀，与正式版一眼可分
            resValue("string", "app_name", "到期提醒D")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 设置页「关于」要显示版本号
        buildConfig = true
        // debug 构建用 resValue 覆盖应用名（到期提醒D）
        resValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // icons 系列已冻结在 1.7.8，BOM 不再管理版本，需显式指定
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    // 离线 OCR：必须用 bundled 版（模型打包进 APK，运行期不联网）。
    // 不可换成 com.google.android.gms:play-services-mlkit-text-recognition-chinese —
    // 那个版本依赖 Google Play 服务、首次需联网下载模型，国产 ROM 多数不可用。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // 单测里用真实的 org.json 实现（Android SDK 里的版本不 support JVM 单测）
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
