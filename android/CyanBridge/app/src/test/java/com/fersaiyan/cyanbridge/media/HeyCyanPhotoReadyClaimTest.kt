package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class HeyCyanPhotoReadyClaimTest {
    @Test
    fun onlyOneCaptureCanOwnThePhotoReadyNotification() {
        val claim = HeyCyanPhotoReadyClaim()
        val first = claim.acquire()

        assertSame(first, claim.current())
        try {
            claim.acquire()
            fail("Expected concurrent photo-ready ownership to be rejected")
        } catch (_: IllegalStateException) {
            // Expected: the first transaction owns the notification.
        }
    }

    @Test
    fun releasingTheOwnerMakesTheNotificationAvailableForRetry() {
        val claim = HeyCyanPhotoReadyClaim()
        val first = claim.acquire()

        claim.release(first)

        assertNull(claim.current())
        val retry = claim.acquire()
        assertSame(retry, claim.current())
        claim.release(retry)
        assertNull(claim.current())
    }
}