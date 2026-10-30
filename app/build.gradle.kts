plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import java.util.Properties

android {
    namespace = "com.streamy.app"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.streamy.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 14
        versionName = "1.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // In-app update feed (GitHub Release asset written by CI). Kept in BuildConfig so
        // tests can pin a fixture and a fork can override without touching code.
        buildConfigField("String", "UPDATE_FEED_URL", "\"https://github.com/renggap/streamy/releases/latest/download/update.json\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // FLEXIBLE_PAGE_SIZES: link libstreamy_lame.so with 16 KB max-page-size so it loads on
                // 16 KB-page devices (Android 15+) and passes Play's 16 KB requirement (NDK < r28
                // defaults to 4 KB). CI verifies the APK with `zipalign -c -P 16`.
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
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
            // Escape for a Java string literal: a password containing " or \ would otherwise
            // break BuildConfig codegen.
            fun javaStr(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            buildConfigField("String", "TEST_HOST", javaStr(lp("streamy.test.host", "")))
            buildConfigField("int", "TEST_PORT", lp("streamy.test.port", "0"))
            buildConfigField("String", "TEST_PASSWORD", javaStr(lp("streamy.test.password", "")))
            buildConfigField("int", "TEST_SID", lp("streamy.test.sid", "1"))
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release signing creds live in gitignored local.properties:
            //   streamy.keystore.file / .alias / .password (+ optional .keyPassword when the key
            //   password differs from the store password). CI writes the same keys from secrets.
            // Unsigned release APKs cannot install (INSTALL_PARSE_FAILED_NO_CERTIFICATES).
            val lp = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { lp.load(it) }
            // file("") throws at configuration time, which would break every build (incl. debug/CI)
            // on a machine without a keystore — only resolve the path when one is configured.
            val ksFile = lp.getProperty("streamy.keystore.file", "").takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
            val ksPass = lp.getProperty("streamy.keystore.password", "")
            if (ksFile != null && ksFile.exists() && ksPass.isNotEmpty()) {
                signingConfigs.create("release") {
                    storeFile = ksFile
                    storePassword = ksPass
                    keyAlias = lp.getProperty("streamy.keystore.alias", "streamy")
                    keyPassword = lp.getProperty("streamy.keystore.keyPassword", ksPass)
                }
                signingConfig = signingConfigs.getByName("release")
            }
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    // JVM tests run against the mockable android.jar whose org.json methods throw
    // "not mocked" — supply the real one (test-only, matches the Android copy).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation("androidx.test:rules:1.6.1")
}
