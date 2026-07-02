plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.minitraxx.whisperflow"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "de.minitraxx.whisperflow"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2"

        // On-Device Whisper: nur arm64 (Zielgeraet Nothing Phone 3a).
        // Auf anderen ABIs fehlt die native Lib -> automatischer Cloud-Fallback.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_SERVER=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF"
                )
                cppFlags += "-std=c++17"
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
