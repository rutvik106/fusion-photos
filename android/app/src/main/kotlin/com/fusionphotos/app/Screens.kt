package com.fusionphotos.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fusionphotos.app.media.LocalPhoto

/** Step 1 — email OTP. The code is typed by the user; in dev the server echoes it back. */
@Composable
internal fun SignInScreen(
    baseUrl: String,
    email: String,
    code: String,
    devCode: String?,
    busy: Boolean,
    onBaseUrl: (String) -> Unit,
    onEmail: (String) -> Unit,
    onCode: (String) -> Unit,
    onSend: () -> Unit,
    onVerify: () -> Unit,
) {
    Text("Sign in", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = baseUrl,
        onValueChange = onBaseUrl,
        label = { Text("Server URL") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(2.dp))
    Text(
        "The emulator reaches your machine at http://10.0.2.2:8080. Plain HTTP is allowed " +
            "only for localhost; anything else must be HTTPS.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF666666),
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = email,
        onValueChange = onEmail,
        label = { Text("Email") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Button(onClick = onSend, enabled = !busy && email.contains('@')) { Text("Send sign-in code") }

    devCode?.let { dev ->
        Spacer(Modifier.height(12.dp))
        Text("Dev code: $dev", fontFamily = FontFamily.Monospace, color = Color(0xFF1B5E20))
    }

    Spacer(Modifier.height(18.dp))
    OutlinedTextField(
        value = code,
        onValueChange = onCode,
        label = { Text("6-digit code") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Button(onClick = onVerify, enabled = !busy && code.isNotBlank()) { Text("Verify and continue") }
}

/** Step 2 — the vault: create (first device) or unlock (returning device). */
@Composable
internal fun UnlockScreen(
    hasVault: Boolean,
    passphrase: String,
    recoveryKey: String?,
    busy: Boolean,
    onPassphrase: (String) -> Unit,
    onPrimary: () -> Unit,
) {
    Text(
        if (hasVault) "Unlock your vault" else "Create your vault",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (hasVault) {
            "Your passphrase never leaves this device — it derives the key that decrypts " +
                "your photos. We cannot reset it."
        } else {
            "Choose a passphrase (at least 12 characters). It never leaves this device, and " +
                "we cannot recover your library without it."
        },
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF444444),
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphrase,
        label = { Text("Vault passphrase") },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onPrimary,
        enabled = !busy && passphrase.length >= if (hasVault) 1 else 12,
    ) {
        Text(if (hasVault) "Unlock" else "Create vault")
    }

    recoveryKey?.let { key ->
        Spacer(Modifier.height(16.dp))
        Text("Save your recovery key", style = MaterialTheme.typography.titleSmall, color = Color(0xFFB00020))
        Spacer(Modifier.height(4.dp))
        Text("This is shown once. It is the only other way in if you forget the passphrase.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Step 3 — discovery. Uploading from the device is the next increment. */
@Composable
internal fun LibraryScreen(
    status: String,
    photos: List<LocalPhoto>?,
    busy: Boolean,
    vaultUnlocked: Boolean,
    hasPermission: Boolean,
    onGrantOrScan: () -> Unit,
) {
    Text("Camera library", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        "Vault key: ${if (vaultUnlocked) "unlocked in memory" else "not held"}",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF444444),
    )
    Spacer(Modifier.height(10.dp))
    Button(onClick = onGrantOrScan, enabled = !busy) {
        Text(if (hasPermission) "Scan camera roll" else "Grant photo access")
    }

    if (status.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }

    photos?.take(5)?.forEach { photo ->
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(photo.displayName, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text(
                "${photo.sizeBytes / 1024} KiB · ${photo.mimeType}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF666666),
            )
        }
    }
    if ((photos?.size ?: 0) > 5) {
        Spacer(Modifier.height(6.dp))
        Text("…and ${photos!!.size - 5} more", style = MaterialTheme.typography.bodySmall)
    }
}
