package com.fusionphotos.app.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The slicing expectations are copied from the server's own tests
 * (`api/cmd/server/validation_multipart_test.go`), so client and server are pinned to the
 * same byte ranges — a resumed multipart upload would silently corrupt a photo otherwise.
 */
class UploadPlanTest {

    @Test
    fun `small resources use a single PUT`() {
        assertFalse(UploadPlan.planFor(1).multipart)
        assertEquals(1, UploadPlan.planFor(1).partCount)
        assertFalse(UploadPlan.planFor(UploadPlan.MULTIPART_THRESHOLD).multipart)
        assertTrue(UploadPlan.planFor(UploadPlan.MULTIPART_THRESHOLD + 1).multipart)
    }

    @Test
    fun `multipart picks an 8 MiB-granular part count like the web client`() {
        val size = 100L * 1024 * 1024 // 100 MiB
        val plan = UploadPlan.planFor(size)
        assertTrue(plan.multipart)
        // The web client decides only the COUNT: ceil(size / 8 MiB). The server then splits
        // the bytes evenly (remainder in the last part), so the effective part is <= 8 MiB —
        // this test pins that shared behaviour, not a literal 8 MiB.
        assertEquals(13, plan.partCount)
        val evenPart = (size + 12) / 13
        assertEquals(evenPart, plan.partSize)
        assertTrue(
            plan.partSize in UploadPlan.MIN_PART_SIZE..UploadPlan.PART_SIZE,
            "part size ${plan.partSize} must be within [5 MiB, 8 MiB]",
        )
        assertEquals(0L..evenPart - 1, UploadPlan.partRange(size, plan.partCount, 0))
        assertEquals(12 * evenPart..size - 1, UploadPlan.partRange(size, plan.partCount, 12))
    }

    @Test
    fun `huge resources widen parts instead of exceeding the server cap`() {
        val plan = UploadPlan.planFor(1024L * 1024 * 1024 * 1024) // 1 TiB
        assertTrue(plan.multipart)
        assertTrue(plan.partCount <= UploadPlan.MAX_PARTS, "part count ${plan.partCount} exceeds the cap")
        assertTrue(plan.partSize >= UploadPlan.MIN_PART_SIZE)
        assertEquals(UploadPlan.MAX_PARTS, plan.partCount)
    }

    @Test
    fun `slice layout matches the server computePartSlices`() {
        // 12 MiB in 2 parts -> 6 MiB each (server: even slicing)
        val size12 = 12L * 1024 * 1024
        assertEquals(0L..6L * 1024 * 1024 - 1, UploadPlan.partRange(size12, 2, 0))
        assertEquals(6L * 1024 * 1024..size12 - 1, UploadPlan.partRange(size12, 2, 1))

        // 17 MiB in 3 parts -> even slicing with the remainder in the LAST part
        val size17 = 17L * 1024 * 1024
        val partLen = (size17 + 2) / 3
        assertEquals(0L..partLen - 1, UploadPlan.partRange(size17, 3, 0))
        assertEquals(partLen..2 * partLen - 1, UploadPlan.partRange(size17, 3, 1))
        assertEquals(2 * partLen..size17 - 1, UploadPlan.partRange(size17, 3, 2))

        // a single part carries the whole resource, however small (server: single part)
        assertEquals(0L..16L, UploadPlan.partRange(17, 1, 0))
    }

    @Test
    fun `slices are contiguous and cover every byte`() {
        val size = 17L * 1024 * 1024
        var next = 0L
        for (i in 0 until 3) {
            val range = UploadPlan.partRange(size, 3, i)
            assertEquals(next, range.first, "part $i must begin where the previous ended")
            next = range.last + 1
        }
        assertEquals(size, next, "slices must cover the whole resource")
    }

    @Test
    fun `bad input is rejected rather than planned`() {
        assertFailsWith<IllegalArgumentException> { UploadPlan.planFor(0) }
        assertFailsWith<IllegalArgumentException> { UploadPlan.partRange(100, 2, 2) }
        assertFailsWith<IllegalArgumentException> { UploadPlan.partRange(100, 0, 0) }
    }
}
