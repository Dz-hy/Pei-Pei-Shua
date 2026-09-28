plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

buildDir = file("build-zen")

android {
    namespace = "com.example.aiassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.aiassistant"
        minSdk = 26
        targetSdk = 36
        // versionCode 跨构建单调递增：(年-2025)*1e8 + MMddHHmm，2046 年内不会溢出 int
        val buildCal = Calendar.getInstance()
        versionCode = (buildCal.get(Calendar.YEAR) - 2025) * 100_000_000 +
            SimpleDateFormat("MMddHHmm").format(Date()).toInt()
        // 每次构建自动变化的版号（如 1.0.08292145），用于确认真机安装的是哪个构建
        versionName = "1.0." + SimpleDateFormat("MMddHHmm").format(Date())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            // CI 通过环境变量注入；本地开发从 ~/.gradle/gradle.properties 或环境变量读取
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: project.findProperty("RELEASE_STORE_PASSWORD") as String? ?: ""
            keyAlias = "peipeishua"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: project.findProperty("RELEASE_KEY_PASSWORD") as String? ?: ""
        }
    }

    buildTypes {
        debug {
            // debug 必须与 release 同一套签名（仓库内 keystore）：两者同包名，签名不一致时
            // release 覆盖安装 debug 会被系统拒绝（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
            // 用户只能卸载重装、数据全丢。默认的 debug 签名是各构建机器 ~/.android 下
            // 随机生成的 keystore（CI runner 每次构建都不同），绝不能用于分发。
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
            "-Xno-param-assertions"
        )
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.paddleocr4android)
    implementation(libs.flexbox)
    implementation(libs.jsoup)
    implementation(libs.wcdb)
}