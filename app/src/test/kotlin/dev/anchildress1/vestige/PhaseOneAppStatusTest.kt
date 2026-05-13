package dev.anchildress1.vestige

import org.junit.Assert.assertEquals
import org.junit.Test

class PhaseOneAppStatusTest {

    @Test
    fun `phaseOneAppStatus stays loading until the shell is ready`() {
        assertEquals(
            PhaseOneAppStatus.LOADING,
            phaseOneAppStatus(permissionGranted = false, lastRequestDenied = false),
        )
    }

    @Test
    fun `phaseOneAppStatus flips to ready once mic permission is granted`() {
        assertEquals(
            PhaseOneAppStatus.READY,
            phaseOneAppStatus(permissionGranted = true, lastRequestDenied = false),
        )
    }

    @Test
    fun `phaseOneAppStatus surfaces mic required after a denial`() {
        assertEquals(
            PhaseOneAppStatus.MIC_REQUIRED,
            phaseOneAppStatus(permissionGranted = false, lastRequestDenied = true),
        )
    }
}
