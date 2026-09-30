package com.fusionphotos.app

import com.fusionphotos.crypto.LibSodiumAdapter
import com.fusionphotos.crypto.Sodium
import com.goterl.lazysodium.SodiumAndroid

/**
 * The Android implementation of `:crypto`'s [Sodium] interface — native libsodium via
 * lazysodium-android.
 *
 * The same [LibSodiumAdapter] is driven by `SodiumJava` in the JVM tests, so the code path
 * that runs on a phone is the one CI already verified against `shared/crypto-vectors.json`.
 * The in-app self-check closes the loop on a real device.
 */
object AndroidSodium {
    val instance: Sodium by lazy { LibSodiumAdapter(SodiumAndroid()) }
}
