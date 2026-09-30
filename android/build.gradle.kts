// Root build. Plugin versions are declared here and applied per module.
//
// :crypto is a pure JVM module: it holds the E2EE wire format, verified against
// shared/crypto-vectors.json on the host JVM. The Android application module (:app) is
// added in the next increment and will apply the Android plugin alongside this one.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.7.3" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
