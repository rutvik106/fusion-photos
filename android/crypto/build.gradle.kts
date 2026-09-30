// The E2EE wire format, shared with the web client and verified against
// ../../../shared/crypto-vectors.json. Pure JVM: no Android dependency, so its tests run
// on the host (fast, and reproducible in CI without an emulator or the Android SDK).
plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // compileOnly: LibSodiumAdapter targets the shared lazysodium API, but each platform
    // supplies its own implementation (lazysodium-android in the app, lazysodium-java in
    // tests). Nothing platform-specific is shipped from this module.
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    // The lazysodium API exposes crypto_pwhash's memlimit as a JNA NativeLong.
    compileOnly("net.java.dev.jna:jna:5.14.0")

    // JVM libsodium for tests only.
    // 5.1.4 (Java 11 target) keeps this module on JVM 17 so the Android app can consume
    // it; 5.2.0 requires JVM 21.
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    // lazysodium declares JNA as provided, so it is not resolved transitively.
    testImplementation("net.java.dev.jna:jna:5.14.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // The vectors live once, at the repo root, and are read from there by every client.
    systemProperty("fusion.cryptoVectors", rootProject.file("../shared/crypto-vectors.json").absolutePath)
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
