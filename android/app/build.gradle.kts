plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.adhopte.emrtdwallet"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.adhopte.emrtdwallet"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.1"
        // Default issuer backend; can be changed at runtime in Settings.
        // Override at build time: ./gradlew assembleDebug -PissuerUrl=https://my-issuer.onrender.com
        // (http://10.0.2.2:8000 is the host machine when running in the Android emulator.)
        val issuerUrl = (project.findProperty("issuerUrl") as String?) ?: "http://10.0.2.2:8000"
        buildConfigField("String", "DEFAULT_ISSUER_URL", "\"$issuerUrl\"")
        // 64-bit and 32-bit ARM phones; x86_64 keeps the emulator working.
        // (Only the optional ZK-proof library lacks a 32-bit build; this app does not use ZK presentation.)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // A fixed, intentionally PUBLIC development key: every build (local or CI) gets the same
    // signature, so a newer APK installs over an older one. Use your own secret key for real releases.
    signingConfigs {
        create("dev") {
            storeFile = rootProject.file("keystore/dev.keystore")
            storePassword = "emrtd-dev"
            keyAlias = "emrtd-dev"
            keyPassword = "emrtd-dev"
            // Sign with every scheme so all Android versions/installers accept the APK
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Robolectric screenshot tests (Roborazzi) render Compose screens on the JVM
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Compress native libraries and extract them at install time. Storing them uncompressed
        // (the modern default) trips some OEM package installers and makes the APK much larger.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.play.services)

    // eMRTD reading (BAC / PACE / AA / CA)
    implementation(libs.jmrtd)
    implementation(libs.scuba.android)

    // EUDI wallet core: secure storage, ISO 18013-5 proximity and OpenID4VP presentation
    implementation(libs.eudi.wallet.core)
    implementation(libs.androidx.biometric)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
