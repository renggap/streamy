plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import java.util.Properties

android {
    namespace = "com.streamy.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.streamy.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "1.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            // Instrumented integration test targets a real DNAS2 server. Creds come from
            // local.properties (gitignored) via BuildConfig so secrets never enter source control.
            // Debug-only: unit tests run against debug, and connectedAndroidTest uses debug.
            val lp = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { lp.load(it) }
            fun lp(k: String, default: String): String = lp.getProperty(k) ?: default
            buildConfigField("String", "TEST_HOST", "\"${lp("streamy.test.host", "")}\"")
            buildConfigField("int", "TEST_PORT", lp("streamy.test.port", "0"))
            buildConfigField("String", "TEST_PASSWORD", "\"${lp("streamy.test.password", "")}\"")
            buildConfigField("int", "TEST_SID", lp("streamy.test.sid", "1"))
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation("androidx.test:rules:1.6.1")
}
