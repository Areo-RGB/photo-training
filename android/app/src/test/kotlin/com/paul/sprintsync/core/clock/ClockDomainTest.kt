package com.paul.sprintsync.core.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockDomainTest {
    @Test
    fun `computeGpsFixAgeNanos clamps small negative skew to zero`() {
        val now = ClockDomain.nowElapsedNanos()
        val slightlyFutureFix = now + 100_000_000L

        val age = ClockDomain.computeGpsFixAgeNanos(slightlyFutureFix)

        assertEquals(0L, age)
    }

    @Test
    fun `computeGpsFixAgeNanos rejects negative skew beyond tolerance`() {
        val now = ClockDomain.nowElapsedNanos()
        val farFutureFix = now + 600_000_000L

        val age = ClockDomain.computeGpsFixAgeNanos(farFutureFix)

        assertNull(age)
    }
}
