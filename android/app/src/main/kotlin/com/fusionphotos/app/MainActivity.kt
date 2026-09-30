package com.fusionphotos.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fusionphotos.app.media.LocalPhoto
import com.fusionphotos.app.media.MediaStoreScanner
import com.fusionphotos.app.net.ApiClient
import com.fusionphotos.app.net.ApiException
import com.fusionphotos.app.session.SessionStore
import com.fusionphotos.app.vault.VaultException
import com.fusionphotos.app.vault.VaultManager
import com.fusionphotos.crypto.CryptoException
import com.fusionphotos.crypto.CryptoVectorChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Stage { SignIn, Unlock, Library }

/**
 * The Android client today: sign in, unlock the vault, then discover the camera library.
 *
 * It is deliberately *thin* — the crypto is `:crypto` (verified against the shared vectors)
 * and the wire shapes are `net/Dtos` (unit-tested). What remains is platform glue; the next
 * increment replaces the discovery screen with a durable WorkManager upload queue.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SessionStore(context) }
    val sodium = remember { AndroidSodium.instance }
    val api = remember { ApiClient({ store.baseUrl }, { store.token }) }
    val vault = remember { VaultManager(sodium, api) }

    var stage by remember { mutableStateOf(Stage.SignIn) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var devCode by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var hasVault by remember { mutableStateOf(true) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var vaultKeyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var photos by remember { mutableStateOf<List<LocalPhoto>?>(null) }
    var selfCheck by remember { mutableStateOf("Encryption self-check: running…") }
    // Editable so a physical device can point at a host other than the emulator default.
    var baseUrl by remember { mutableStateOf(store.baseUrl) }

    // The phone's own libsodium must reproduce the shared vectors: say so on screen rather
    // than assuming the host-verified code path behaves the same on device.
    LaunchedEffect(Unit) {
        selfCheck = runCatching {
            val vectors = VectorsJson.load(context)
            val results = withContext(Dispatchers.Default) { CryptoVectorChecks.run(sodium, vectors) }
            val failed = results.count { !it.passed }
            if (failed == 0) {
                "Encryption self-check: ${results.size}/${results.size} passed"
            } else {
                "Encryption self-check: $failed of ${results.size} FAILED"
            }
        }.getOrElse { "Encryption self-check could not run: ${it.message}" }
    }

    fun scanLibrary() {
        scope.launch {
            busy = true
            error = null
            status = "Scanning the camera library…"
            try {
                val found = withContext(Dispatchers.IO) { MediaStoreScanner.scan(context) }
                photos = found
                val mib = found.sumOf { it.sizeBytes } / (1024 * 1024)
                status = "${found.size} photo(s) in the camera roll · $mib MiB"
            } catch (t: Throwable) {
                error = "Scan failed: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    fun hasPhotoPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        MediaStoreScanner.readImagesPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scanLibrary()
            } else {
                error = "Photo access was denied. The app cannot back up a library it cannot " +
                    "read, and it will not upload anything without it."
            }
        }

    fun sendCode() {
        scope.launch {
            busy = true
            error = null
            try {
                // Persist first: the API client reads the store, so the field edits take effect.
                store.baseUrl = baseUrl
                val res = api.requestOtp(email.trim())
                devCode = res.devCode
                status = if (res.devCode != null) {
                    "Dev mode: the server returned the code below (no email is sent)."
                } else {
                    "Code sent to ${email.trim()}."
                }
            } catch (e: ApiException) {
                error = "Could not send a code (${e.status}): ${e.message}"
            } catch (t: Throwable) {
                error = "Could not reach the server at ${store.baseUrl}: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    fun verifyCode() {
        scope.launch {
            busy = true
            error = null
            try {
                val res = api.verifyOtp(email.trim(), code.trim())
                store.token = res.sessionToken
                store.userId = res.userId
                hasVault = res.hasVault
                status = ""
                stage = Stage.Unlock
            } catch (e: ApiException) {
                error = "Sign-in failed (${e.status}): ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun unlockOrCreate() {
        scope.launch {
            busy = true
            error = null
            try {
                if (hasVault) {
                    vaultKeyBytes = vault.unlock(passphrase)
                } else {
                    val created = vault.create(passphrase)
                    // Keep the key: without it the app cannot encrypt anything it uploads.
                    vaultKeyBytes = created.vaultKey
                    recoveryKey = created.recoveryKey
                    hasVault = true
                }
                passphrase = ""
                status = ""
                stage = Stage.Library
            } catch (e: VaultException) {
                error = e.message
            } catch (e: CryptoException) {
                error = if (hasVault) "Wrong passphrase." else "Could not create the vault: ${e.message}"
            } catch (t: Throwable) {
                error = "Vault error: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Fusion Photos", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(2.dp))
        Text(selfCheck, style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444))
        Spacer(Modifier.height(16.dp))

        when (stage) {
            Stage.SignIn -> SignInScreen(
                baseUrl = baseUrl,
                email = email,
                code = code,
                devCode = devCode,
                busy = busy,
                onBaseUrl = { baseUrl = it },
                onEmail = { email = it },
                onCode = { code = it },
                onSend = ::sendCode,
                onVerify = ::verifyCode,
            )

            Stage.Unlock -> UnlockScreen(
                hasVault = hasVault,
                passphrase = passphrase,
                recoveryKey = recoveryKey,
                busy = busy,
                onPassphrase = { passphrase = it },
                onPrimary = ::unlockOrCreate,
            )

            Stage.Library -> LibraryScreen(
                status = status,
                photos = photos,
                busy = busy,
                vaultUnlocked = vaultKeyBytes != null,
                hasPermission = hasPhotoPermission(),
                onGrantOrScan = {
                    if (hasPhotoPermission()) {
                        scanLibrary()
                    } else {
                        permissionLauncher.launch(MediaStoreScanner.readImagesPermission())
                    }
                },
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFB00020), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

