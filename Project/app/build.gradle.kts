plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.csproject"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.csproject"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // tells NDK which cpu architecture to compile c++ code for; without this gradle builds for every
        // architecture which makes the build slow and the apk unnecessarily large.
        // arm64-v8a covers all modern 64-bit android phones and is required for arm neon intrinsics.
        // x86_64 (emulator) is excluded because arm_neon.h is not available on that target.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        //passes the compiler flags to the cmake build that compiles the c++ files using c++17 standard which we will use in NEON SIMD and GPU shader code
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    //tells gradle that this project contains c++ code managed by cmake
    // without this gradle will only compile the kotlin and ignores the c++ files
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    //googles jetpack wrapper around the low level camera2 API
    //all 4 modules must be in the same version to stay compatible
    //core -> base interface, camera2-> actual camera implemtation that drives the hardware
    //lifecycle-> automatically open/close the camera, view-> provide previewView a ready made view of display
    //var cameraxVersion="1.3.1"
    //implementation("androidx.camera:camera-core:$cameraxVersion")
    //implementation("androidx.camera:camera-camera2:$cameraxVersion")
    //implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    //implementation("androidx.camera:camera-view:$cameraxVersion")

    //we also need this for camera permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
}
