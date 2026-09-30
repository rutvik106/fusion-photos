package com.fusionphotos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fusionphotos.crypto.CryptoVectorChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device half of the cross-client contract.
 *
 * CI can only run the JVM adapter (the Android one needs real Android libsodium), so this
 * instrumentation test runs the SAME shared vector suite against [AndroidSodium] on a
 * device/emulator:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * If the Android client ever stops matching the web client byte-for-byte, this fails.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSodiumVectorTest {

    @Test
    fun matchesEverySharedCryptoVector() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vectors = VectorsJson.load(context)
        val results = CryptoVectorChecks.run(AndroidSodium.instance, vectors)

        results.forEach { println("  ${if (it.passed) "PASS" else "FAIL"}  ${it.name}") }
        assertEquals("expected the full vector suite to run", 22, results.size)
        val failed = results.filterNot { it.passed }.map { "${it.name}: ${it.detail}" }
        assertTrue("crypto vector failures: $failed", failed.isEmpty())
    }
}
