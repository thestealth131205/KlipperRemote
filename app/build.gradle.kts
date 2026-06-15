import java.util.Properties

// Entwickler-/Herausgeber-Identität (für Support, Play Console & Play Protect):
//   Entwickler:    Dennis Bassy
//   Google-Konto:  thestealth131205@googlemail.com
//   Support-Mail:  info@letheapp.de
// Hinweis: Die Play-Protect-Warnung "App von einem unbekannten Entwickler" beim
// Sideload einer APK lässt sich NICHT über die build.gradle entfernen. Sie
// verschwindet nur, wenn die App über die Google Play Console mit genau diesem
// Konto (thestealth131205@googlemail.com) veröffentlicht und mit dem hier
// hinterlegten Release-Keystore signiert wird.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Signing config is sourced from a local keystore.properties (developer machine)
// or from environment variables (CI / GitHub Actions). Neither the keystore nor
// the passwords are ever committed to the repository.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.klipperremote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.klipperremote.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 52
        versionName = "1.0.51"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only attach the signing config when a keystore is actually available,
            // so unsigned local builds (e.g. CI forks without secrets) still succeed.
            if (signingValue("storeFile", "KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("Boolean", "LICENSE_CHECK_ENABLED", "true")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("Boolean", "LICENSE_CHECK_ENABLED", "false")
        }
    }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.13" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.datastore)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.webrtc)
    debugImplementation(libs.compose.ui.tooling)
}
