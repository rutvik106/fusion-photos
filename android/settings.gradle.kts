rootProject.name = "fusion-photos-android"

// AGP and AndroidX live on Google's Maven repo; Kotlin and lazysodium on Maven Central.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// :app is the Android client; :crypto is a pure JVM module holding the E2EE wire format,
// verified against shared/crypto-vectors.json on the host (no SDK or emulator needed).
include(":crypto")
include(":app")
