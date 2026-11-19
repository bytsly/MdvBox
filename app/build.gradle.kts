plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 每次改动递增 0.01，同时用于 APK 文件名
val appVersionName = "1.44"

android {
    namespace = "com.example.mmdvm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mmdvm"
        minSdk = 29
        targetSdk = 35
        versionCode = 48
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

/**
 * 输出带版本号的 APK：apk/MMDVM盒子_v1.10.apk
 */
tasks.register("exportApk", org.gradle.api.tasks.Copy::class) {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("app-debug.apk")
    }
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "MDVBOX_v$appVersionName.apk" }
}
