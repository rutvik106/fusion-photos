package com.fusionphotos.app.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sizing rules are pure so they can be pinned here rather than eyeballed on a phone, and they
 * are the part most likely to be quietly wrong: an upscaled thumbnail, a sample size that throws
 * away more pixels than the preview needs, or a rotation applied twice.
 */
class DerivativeGeneratorTest {

    @Test
    fun `scaling fits the long edge and keeps the aspect ratio`() {
        assertEquals(1600 to 1200, DerivativeGenerator.scaledSize(4000, 3000, 1600))
        assertEquals(1200 to 1600, DerivativeGenerator.scaledSize(3000, 4000, 1600))
        assertEquals(512 to 384, DerivativeGenerator.scaledSize(4000, 3000, 512))
    }

    @Test
    fun `scaling never upscales a small photo`() {
        assertEquals(800 to 600, DerivativeGenerator.scaledSize(800, 600, 1600))
        assertEquals(300 to 200, DerivativeGenerator.scaledSize(300, 200, 512))
    }

    @Test
    fun `scaling rounds up and never reaches zero`() {
        // 2000 * 1600/3000 = 1066.67: rounding, not truncation.
        assertEquals(1600 to 1067, DerivativeGenerator.scaledSize(3000, 2000, 1600))
        // A panorama must not collapse to a zero-height bitmap.
        assertEquals(1600 to 1, DerivativeGenerator.scaledSize(10000, 1, 1600))
        assertEquals(0 to 0, DerivativeGenerator.scaledSize(0, 0, 1600))
    }

    @Test
    fun `the decode sample is the largest power of two that still fills the preview`() {
        // Sampling by 2 leaves 2000px (still above 1600, so quality is kept); by 4 it would leave
        // 1000 and the preview would have to be upscaled — exactly what it exists to avoid.
        assertEquals(2, DerivativeGenerator.sampleSize(4000, 3000, 1600))
        assertEquals(4, DerivativeGenerator.sampleSize(6400, 4800, 1600))
        assertEquals(1, DerivativeGenerator.sampleSize(1600, 1200, 1600))
        assertEquals(1, DerivativeGenerator.sampleSize(100, 100, 1600))
    }

    @Test
    fun `MediaStore reports degrees, so only real rotations are applied`() {
        // MediaStore's ORIENTATION column is 0/90/180/270 — NOT the EXIF 1-8 tag encoding. Reading
        // it as EXIF (where 6 means "rotate 90") would rotate upright photos.
        assertEquals(0, DerivativeGenerator.rotationForOrientation(0))
        assertEquals(90, DerivativeGenerator.rotationForOrientation(90))
        assertEquals(180, DerivativeGenerator.rotationForOrientation(180))
        assertEquals(270, DerivativeGenerator.rotationForOrientation(270))
        assertEquals(0, DerivativeGenerator.rotationForOrientation(1))
        assertEquals(0, DerivativeGenerator.rotationForOrientation(6))
        assertEquals(0, DerivativeGenerator.rotationForOrientation(-90))
    }

    @Test
    fun `the derivative geometry matches the web client`() {
        // Both clients write the same manifest, so these two numbers are a cross-client contract.
        assertEquals(512, DerivativeGenerator.THUMBNAIL_MAX_PX)
        assertEquals(1600, DerivativeGenerator.PREVIEW_MAX_PX)
        assertEquals("thumbnail", DerivativeGenerator.THUMBNAIL)
        assertEquals("preview", DerivativeGenerator.PREVIEW)
    }
}
