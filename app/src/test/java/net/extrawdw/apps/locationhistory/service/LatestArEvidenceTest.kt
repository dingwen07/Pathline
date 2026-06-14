package net.extrawdw.apps.locationhistory.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestArEvidenceTest {

    @Test
    fun matchingExitClearsCurrentEvidence() {
        val evidence = LatestArEvidence()

        evidence.enter("IN_VEHICLE", confidence = 90)
        evidence.exit("IN_VEHICLE")

        assertNull(evidence.current().activity)
        assertNull(evidence.current().confidence)
        assertFalse(evidence.isActivity("IN_VEHICLE"))
    }

    @Test
    fun unrelatedExitDoesNotClearCurrentEvidence() {
        val evidence = LatestArEvidence()

        evidence.enter("WALKING", confidence = 90)
        evidence.exit("IN_VEHICLE")

        assertEquals(ArEvidence("WALKING", 90), evidence.current())
        assertTrue(evidence.isActivity("WALKING"))
    }

    @Test
    fun laterEnterReplacesPreviousEvidence() {
        val evidence = LatestArEvidence()

        evidence.enter("IN_VEHICLE", confidence = 90)
        evidence.enter("STILL", confidence = 90)

        assertEquals(ArEvidence("STILL", 90), evidence.current())
        assertTrue(evidence.isActivity("STILL"))
    }
}
