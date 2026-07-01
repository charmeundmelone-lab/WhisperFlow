plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.minitraxx.whisperflow"
    compileSdk = 35
    // 27.3.13750724: aktuelle Default-NDK-Version auf GitHub Actions ubuntu-latest Runnern
    // (Stand 2026). Lokal ggf. via `sdkmanager --install "ndk;27.3.13750724"` nachinstallieren.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "de.minitraxx.whisperflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Nur arm64-v8a: Zielgerät (Nothing Phone 3a, Snapdragon 7s Gen 3) ist rein
            // arm64. Spart Build-Zeit und APK-Größe gegenüber allen ABIs.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    signingConfigs {
        create("persistent") {
            storeFile = file("keystore/debug.jks")
            storePassword = "whisperflow"
            keyAlias = "whisperflow"
            keyPassword = "whisperflow"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
        }
        release {
            isMinifyEnabled = false
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
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    debugImplementation(libs.compose.ui.tooling)
}
