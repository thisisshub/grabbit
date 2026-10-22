import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Beta settings live in local.properties (git-ignored): grabbit.guideKey (bundled streaming-guide key)
// and grabbit.feedbackRepo (owner/repo that "Report a bug" opens issues on).
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(name: String): String = (local.getProperty(name) ?: System.getenv(name.replace('.', '_').uppercase())).orEmpty()

android {
    namespace = "dev.usenetstream"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.usenetstream"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-beta.1"
        buildConfigField("String", "GUIDE_KEY", "\"${localProp("grabbit.guideKey")}\"")
        buildConfigField("String", "FEEDBACK_REPO", "\"${localProp("grabbit.feedbackRepo")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phone (arm64) and the Hisense TV (32-bit ARM); keeps the FFmpeg decoder's native code small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // Signed with this Mac's key (~/.android/debug.keystore): beta updates install over each other only while
            // that file is kept, so back it up. Not a Play Store key.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
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

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":engine"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("androidx.tv:tv-material:1.0.0")
    // AC3 / E-AC3 / DTS / TrueHD audio in software for TVs and phones without those decoders (Jellyfin's build of the Media3 extension).
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    testImplementation(kotlin("test"))

    // Run on the phone with `am instrument` (connectedAndroidTest would uninstall the app and wipe its data).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
