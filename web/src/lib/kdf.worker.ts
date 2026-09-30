/// <reference lib="webworker" />
/**
 * KDF worker: Argon2id is expensive (~256 MiB, seconds of CPU). Running it here keeps
 * the main thread — and the whole UI — responsive while the key is being derived.
 * Receives {id, passphrase, saltB64}, answers {id, kekB64} or {id, error}.
 */
import _sodium from 'libsodium-wrappers-sumo'

export interface KdfRequest {
  id: number
  passphrase: string
  saltB64: string
}

const ctx = self as unknown as Worker

ctx.onmessage = async (e: MessageEvent<KdfRequest>) => {
  const { id, passphrase, saltB64 } = e.data
  try {
    const s = await _sodium.ready.then(() => _sodium)
    const salt = s.from_base64(saltB64, s.base64_variants.ORIGINAL)
    const kek = s.crypto_pwhash(
      32,
      passphrase,
      salt,
      s.crypto_pwhash_OPSLIMIT_MODERATE,
      s.crypto_pwhash_MEMLIMIT_MODERATE,
      s.crypto_pwhash_ALG_ARGON2ID13,
    )
    // zero nothing here: worker dies/reuses; kek goes to caller via postMessage
    ctx.postMessage({
      id,
      kekB64: s.to_base64(kek, s.base64_variants.ORIGINAL),
    })
  } catch (err) {
    ctx.postMessage({ id, error: err instanceof Error ? err.message : String(err) })
  }
}
