package com.fusionphotos.app.session

/**
 * Where the local vault key lives between launches.
 *
 * The vault key must be available to a **background** worker with the screen off and the app
 * process long gone, so it cannot be memory-only any more: it is wrapped by a hardware-backed
 * Keystore key (AES-GCM, per-device, not exportable) and only the wrapped bytes are stored.
 *
 * This is the trade-off the plan asks for ("device secure storage protects the local vault
 * key"), and it is worth stating plainly:
 *
 *  - **Gained:** backups continue after a restart, which is the point of a backup app.
 *  - **Given up:** while the key is enrolled, an attacker who has the unlocked device *and*
 *    can run code inside this app's sandbox can reach the key. That was not true when the key
 *    existed only in memory.
 *  - **Unchanged:** the key never leaves the device, is never uploaded, is not included in
 *    Android backups (`allowBackup=false`), is wiped on sign-out, and a factory reset or app
 *    data clear removes it — the passphrase or recovery key is still the only way in after that.
 *
 * If no Keystore is usable, [save] reports failure and backup falls back to running only while
 * the app is open; nothing silently pretends to be durable.
 */
interface KeyVault {
    /** Wraps and stores the vault key. Returns false when the device cannot protect it. */
    fun save(vaultKey: ByteArray): Boolean

    /** The vault key, or null when none is enrolled / it can no longer be unwrapped. */
    fun load(): ByteArray?

    fun clear()

    /** True when this device can protect a key at rest (used to be honest in the UI). */
    fun isUsable(): Boolean
}
