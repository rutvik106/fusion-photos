plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fusionphotos.app"
    compileSdk = 36
    // Only build-tools 36.0.0 is installed here; pin it so AGP never tries to fetch another.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fusionphotos.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Ship the SAME vectors file the web client and the JVM tests verify against — the app
    // checks itself against the cross-client contract on device.
    sourceSets["main"].assets.srcDir("../../shared")

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}

dependencies {
    implementation(project(":crypto"))

    // Native libsodium. The AAR bundles arm64-v8a / armeabi-v7a / x86 / x86_64.
    // 5.2.0 (not 5.1.0) because its libsodium.so is built 16 KB page-size compatible
    // (PT_LOAD p_align 16384 vs 4096) — required by Android 15+ devices and by Google Play.
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Durable background backup: the plan asks for "MediaStore and WorkManager with network,
    // battery and retry constraints … jobs must recover across restart".
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // On-device verification of the Android adapter against the shared vectors.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    // JVM unit tests for the pure logic (upload planning, JSON mapping) — no device needed.
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // The app maps JSON with org.json (built into Android); the real artifact makes that
    // mapping testable on the host.
    testImplementation("org.json:json:20240303")
    // A JVM libsodium so the vault round-trip can be tested without a device. The :crypto
    // module deliberately takes lazysodium as compileOnly, so the app supplies it here.
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.14.0")
}
