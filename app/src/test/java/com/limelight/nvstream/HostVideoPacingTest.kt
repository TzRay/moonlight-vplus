package com.limelight.nvstream

import org.junit.Assert.*
import org.junit.Test

class HostVideoPacingTest {
    @Test fun requiresOptInAndAnExplicitHostCapability() {
        assertEquals("smooth-v1", HostVideoPacing.request(true, false, "smooth-v1"))
        assertEquals("smooth-v1", HostVideoPacing.request(true, false, "legacy, smooth-v1 future"))
        assertNull(HostVideoPacing.request(false, false, "smooth-v1"))
        assertNull(HostVideoPacing.request(true, true, "smooth-v1"))
        for (capability in listOf(null, "", "smooth-v2", "smooth-v1x", "1")) {
            assertNull(HostVideoPacing.request(true, false, capability))
        }
    }

    @Test fun missingOrMismatchedAcknowledgementIsNotReportedAsActive() {
        assertTrue(HostVideoPacing.acknowledged("smooth-v1", "smooth-v1"))
        assertFalse(HostVideoPacing.acknowledged("smooth-v1", null))
        assertFalse(HostVideoPacing.acknowledged("smooth-v1", "legacy"))
        assertFalse(HostVideoPacing.acknowledged(null, "smooth-v1"))
    }
}
