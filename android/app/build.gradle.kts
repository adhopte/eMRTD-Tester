plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.adhopte.emrtdwallet"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.adhopte.emrtdwallet"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Default issuer backend; can be changed at runtime in Settings.
        // Override at build time: ./gradlew assembleDebug -PissuerUrl=https://my-issuer.onrender.com
        // (http://10.0.2.2:8000 is the host machine when running in the Android emulator.)
        val issuerUrl = (project.findProperty("issuerUrl") as String?) ?: "http://10.0.2.2:8000"
        buildConfigField("String", "DEFAULT_ISSUER_URL", "\"$issuerUrl\"")
        // NFC phones are arm64; x86_64 keeps the emulator working
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
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
}
