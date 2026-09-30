package com.fusionphotos.app.net

import com.fusionphotos.crypto.WrappedVault
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server treats the vault blob as opaque JSON, so these shapes are a contract between
 * the Go API, the web client and this client. Getting them wrong would make existing
 * libraries unreadable on Android.
 */
class DtosTest {

    @Test
    fun `otp verify maps the session fields`() {
        val res = Dtos.parseOtpVerify(
            JSONObject("""{"sessionToken":"s","userId":"u","hasVault":true}"""),
        )
        assertEquals("s", res.sessionToken)
        assertEquals("u", res.userId)
        assertTrue(res.hasVault)
    }

    @Test
    fun `otp request devCode is absent outside dev mode`() {
        assertNull(Dtos.parseOtpRequest(JSONObject("""{"ok":true}""")).devCode)
        assertEquals("123456", Dtos.parseOtpRequest(JSONObject("""{"ok":true,"devCode":"123456"}""")).devCode)
    }

    @Test
    fun `me maps the account identity`() {
        val me = Dtos.parseMe(JSONObject("""{"userId":"u1","email":"a@b.com"}"""))
        assertEquals("u1", me.userId)
        assertEquals("a@b.com", me.email)
    }

    @Test
    fun `an account with no vault parses to null`() {
        assertNull(Dtos.parseVaultResponse(JSONObject("""{"wrapped":null}""")))
    }

    @Test
    fun `the structured blob round-trips through the wire format`() {
        val blob = Dtos.VaultBlob(
            passphrase = WrappedVault(kdfSalt = "c2FsdA==", wrapped = "d3JhcA==", v = 1),
            recovery = WrappedVault(kdfSalt = "", wrapped = "cmVj", v = 1),
        )
        val body = Dtos.vaultPutBody(blob)
        val parsed = Dtos.parseVaultBlob(body.getJSONObject("wrapped"))
        assertEquals(blob.passphrase, parsed.passphrase)
        assertEquals(blob.recovery, parsed.recovery)
    }

    @Test
    fun `a legacy flat envelope is read as the passphrase envelope`() {
        // Pre-recovery-key vaults stored {kdfSalt, wrapped, v} at the top level.
        val legacy = JSONObject("""{"kdfSalt":"OLD","wrapped":"BLOB","v":1}""")
        val parsed = Dtos.parseVaultBlob(legacy)
        assertEquals(WrappedVault("OLD", "BLOB", 1), parsed.passphrase)
        assertNull(parsed.recovery)
    }

    @Test
    fun `a recovery-only blob leaves passphrase null`() {
        val parsed = Dtos.parseVaultBlob(
            JSONObject("""{"recovery":{"kdfSalt":"","wrapped":"R","v":1}}"""),
        )
        assertNull(parsed.passphrase)
        assertEquals(WrappedVault("", "R", 1), parsed.recovery)
    }

    @Test
    fun `an unknown version defaults to the current crypto version`() {
        val parsed = Dtos.parseVaultBlob(JSONObject("""{"passphrase":{"kdfSalt":"s","wrapped":"w"}}"""))
        assertEquals(Dtos.VaultBlob.CRYPTO_VERSION, parsed.passphrase?.v)
    }
}
