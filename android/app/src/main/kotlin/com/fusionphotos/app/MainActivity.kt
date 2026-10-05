package com.fusionphotos.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import com.fusionphotos.app.session.KeystoreKeyVault
import com.fusionphotos.app.session.SessionStore
import com.fusionphotos.app.upload.PhotoUploader
import com.fusionphotos.app.upload.QueueCounts
import com.fusionphotos.app.upload.QueueItem
import com.fusionphotos.app.upload.QueueState
import com.fusionphotos.app.upload.SqliteUploadQueueStore
import com.fusionphotos.app.vault.VaultException
import com.fusionphotos.app.vault.VaultManager
import com.fusionphotos.app.work.BackupNotifications
import com.fusionphotos.app.work.BackupScheduler
import com.fusionphotos.crypto.CryptoException
import com.fusionphotos.crypto.CryptoVectorChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val keyVault = remember { KeystoreKeyVault(context) }
    val queue = remember { SqliteUploadQueueStore(context) }

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
    var uploadStatus by remember { mutableStateOf("") }
    var queueCounts by remember { mutableStateOf<QueueCounts?>(null) }
    var keyEnrolled by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(true) }
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

    suspend fun refreshQueue() {
        val counts = withContext(Dispatchers.IO) { queue.counts() }
        val enrolled = withContext(Dispatchers.IO) { keyVault.load() != null }
        val allowed = withContext(Dispatchers.IO) { BackupNotifications.notificationsAllowed(context) }
        queueCounts = counts
        keyEnrolled = enrolled
        notificationsAllowed = allowed
    }

    // Register the durable heartbeat once per process — it is what makes backup survive a
    // restart — and then keep the on-screen queue counts fresh while the library is visible.
    LaunchedEffect(stage) {
        withContext(Dispatchers.IO) { BackupScheduler.ensurePeriodic(context) }
        while (stage == Stage.Library) {
            refreshQueue()
            delay(1500)
        }
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
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            // Photo access is the gate: without it there is nothing to read. Location is a
            // fidelity upgrade — Android 10+ zeroes GPS for apps that do not hold it — so a
            // refusal is reported plainly rather than treated as a failure.
            val locationDenied =
                MediaStoreScanner.locationPermission()?.let { grants[it] == false } ?: false
            if (hasPhotoPermission()) {
                error = null
                if (locationDenied) {
                    status = "Photo access granted. Location was declined, so Android strips GPS " +
                        "from what the app reads — backups still work, just without location."
                }
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

    /** Android 13+ gates notifications; without it a foreground backup is invisible. */
    fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    /** Start a visible (foreground) backup and say plainly whether progress will be seen. */
    fun startVisibleBackup() {
        notificationsAllowed = BackupNotifications.notificationsAllowed(context)
        BackupScheduler.ensurePeriodic(context)
        BackupScheduler.backupNow(context, manual = true)
        uploadStatus = if (notificationsAllowed) {
            "Backing up now — progress appears in a notification."
        } else {
            "Backing up now — notifications are off, so progress will not be visible."
        }
    }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permission granted or not, the backup proceeds — visibility is the only difference.
            startVisibleBackup()
        }

    /**
     * Ask for notifications first, because a long backup is exactly the case where the user
     * should be able to watch it. A refusal does not block the work: the queue still runs.
     */
    fun askThenBackUp() {
        if (needsNotificationPermission()) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVisibleBackup()
        }
    }

    /**
     * Queue every discovered photo and let WorkManager do the work.
     *
     * The enqueue is cheap on purpose: the ref comes from MediaStore metadata rather than from
     * hashing every original, so pressing this with a full camera roll does not read gigabytes
     * of untouched files. Rows already in the queue keep their state, so pressing it again only
     * adds what is genuinely new.
     */
    fun queueAll() {
        val list = photos
        if (list.isNullOrEmpty()) {
            error = "Scan the camera roll first."
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                val items = list.map { photo ->
                    QueueItem(
                        clientRef = PhotoUploader.clientRefForMedia(
                            mediaId = photo.id,
                            sizeBytes = photo.sizeBytes,
                            dateModifiedMs = photo.dateModifiedMs,
                        ),
                        mediaId = photo.id,
                        uri = photo.uri.toString(),
                        displayName = photo.displayName,
                        mimeType = photo.mimeType.ifBlank { "application/octet-stream" },
                        capturedAtMs = photo.dateTakenMs,
                        state = QueueState.PENDING,
                        attempts = 0,
                        lastError = null,
                        assetId = null,
                    )
                }
                val added = withContext(Dispatchers.IO) { queue.enqueue(items) }
                val known = items.size - added
                askThenBackUp()
                uploadStatus = "$added queued" +
                    (if (known > 0) ", $known already known" else "") +
                    " · backup scheduled"
                refreshQueue()
            } catch (t: Throwable) {
                error = "Could not queue the photos: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Ask WorkManager to run the queue now, visibly (see [askThenBackUp]). */
    fun backupNow() = askThenBackUp()

    /** Return dead-lettered rows to the queue; the user has decided to try again. */
    fun retryFailed() {
        scope.launch {
            val revived = withContext(Dispatchers.IO) { queue.retryDead(System.currentTimeMillis()) }
            askThenBackUp()
            uploadStatus = "$revived failed item(s) queued again"
            refreshQueue()
        }
    }

    /** Housekeeping: drop the finished rows once they are no longer interesting. */
    fun clearFinished() {
        scope.launch {
            withContext(Dispatchers.IO) { queue.clearUploaded() }
            refreshQueue()
        }
    }

    fun unlockOrCreate() {
        scope.launch {
            busy = true
            error = null
            try {
                val key = if (hasVault) {
                    vault.unlock(passphrase)
                } else {
                    val created = vault.create(passphrase)
                    recoveryKey = created.recoveryKey
                    hasVault = true
                    created.vaultKey
                }
                // Hold it for this session AND enrol it in the Keystore, so the background
                // worker can keep uploading after the process — and the screen — is gone.
                vaultKeyBytes = key
                keyEnrolled = withContext(Dispatchers.IO) { keyVault.save(key) }
                withContext(Dispatchers.IO) { BackupScheduler.ensurePeriodic(context) }
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
                keyEnrolled = keyEnrolled,
                notificationsAllowed = notificationsAllowed,
                hasPermission = hasPhotoPermission(),
                uploadStatus = uploadStatus,
                counts = queueCounts,
                onGrantOrScan = {
                    // Ask for whatever is actually missing: photo access, and on Android 10+ the
                    // location permission that stops MediaProvider redacting GPS. Requesting only
                    // the photo permission (as this did before) meant the location prompt never
                    // appeared once photo access had been granted.
                    val missing = MediaStoreScanner.requiredPermissions().filter {
                        ContextCompat.checkSelfPermission(context, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) {
                        scanLibrary()
                    } else {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                },
                onQueueAll = ::queueAll,
                onBackUpNow = ::backupNow,
                onRetryFailed = ::retryFailed,
                onClearFinished = ::clearFinished,
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFB00020), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

