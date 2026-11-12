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
        versionName = "1.3.0"
        // x-release-please-end
        val (major, minor, patch) = versionName!!.split(".").map { it.toInt() }
        versionCode = major * 10000 + minor * 100 + patch
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

    testImplementation("junit:junit:4.13.2")
    // 单测里用真实的 org.json 实现（Android SDK 里的版本不 support JVM 单测）
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
