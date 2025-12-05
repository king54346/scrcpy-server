plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.genymobile.scrcpy"
    compileSdk = 35
    ndkVersion = "27.1.12297006"  // 改成你实际有的版本
    defaultConfig {
        applicationId = "com.genymobile.scrcpy"
        minSdk = 30
        targetSdk = 35
        versionCode=30303
        versionName="3.3.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // 只保留 64 位架构
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags.add("-std=c++17")
                arguments.add("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        buildConfig = true
        aidl =true
    }
}

dependencies {
    //    androidx.core.ktx - Android KTX 核心扩展，提供 Kotlin 友好的 API
    implementation(libs.androidx.core.ktx)
    //  用于在 Kotlin 中使用协程的主接口
    implementation(libs.kotlinx.coroutines.core)
    //  在协程中支持 Android 主线程
    implementation(libs.kotlinx.coroutines.android)

    //    测试库
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}