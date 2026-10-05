package com.fusionphotos.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The permission rules, pinned.
 *
 * The interesting one is location: Android 10+ MediaProvider zeroes a media file's GPS values for
 * apps that read through the provider without `ACCESS_MEDIA_LOCATION`. A backup taken without it
 * therefore stores photos whose location is silently blank — the tags are still there, pointing at
 * zeros, which is why it went unnoticed until an upload was decrypted and diffed against the file
 * on the phone. These tests pin both the request and the opt-out.
 */
class MediaPermissionsTest {

    @Test
    fun `the photo permission follows the platform split`() {
        assertEquals(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            MediaStoreScanner.readImagesPermission(28),
        )
        assertEquals(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            MediaStoreScanner.readImagesPermission(32),
        )
        assertEquals(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            MediaStoreScanner.readImagesPermission(33),
        )
        assertEquals(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            MediaStoreScanner.readImagesPermission(35),
        )
    }

    @Test
    fun `location is a permission only android 10 and later has`() {
        assertNull(MediaStoreScanner.locationPermission(28))
        assertEquals(
            android.Manifest.permission.ACCESS_MEDIA_LOCATION,
            MediaStoreScanner.locationPermission(29),
        )
        assertEquals(
            android.Manifest.permission.ACCESS_MEDIA_LOCATION,
            MediaStoreScanner.locationPermission(35),
        )
    }

    @Test
    fun `scanning asks for location alongside photo access on android 10 plus`() {
        assertEquals(
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            MediaStoreScanner.requiredPermissions(28).toList(),
        )
        assertEquals(
            listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION,
            ),
            MediaStoreScanner.requiredPermissions(29).toList(),
        )
        assertEquals(
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION,
            ),
            MediaStoreScanner.requiredPermissions(33).toList(),
        )
    }

    @Test
    fun `the unredacted original is requested only where the platform redacts and we may opt out`() {
        // Below Android 10 there is nothing to opt out of.
        assertFalse(MediaBytes.useOriginalRequest(28, hasLocationPermission = true))
        // Android 10+: without the permission `setRequireOriginal` throws, so do not ask.
        assertFalse(MediaBytes.useOriginalRequest(29, hasLocationPermission = false))
        assertTrue(MediaBytes.useOriginalRequest(29, hasLocationPermission = true))
        assertTrue(MediaBytes.useOriginalRequest(33, hasLocationPermission = true))
    }
}
