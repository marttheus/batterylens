package com.batterylens.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeDiagnosticsTest {
    @Test fun speedTiers() {
        assertEquals(ChargeSpeed.UNKNOWN, ChargeDiagnostics.speedFor(null))
        assertEquals(ChargeSpeed.SLOW, ChargeDiagnostics.speedFor(5f))
        assertEquals(ChargeSpeed.NORMAL, ChargeDiagnostics.speedFor(10f))
        assertEquals(ChargeSpeed.FAST, ChargeDiagnostics.speedFor(20f))
        assertEquals(ChargeSpeed.SUPER_FAST, ChargeDiagnostics.speedFor(40f))
    }

    @Test fun idealConditionsHaveNoLimiters() {
        val d = ChargeDiagnostics.diagnose(35f, 40, 30f, screenOn = false, plugType = 1)
        assertTrue(d.limiters.isEmpty())
    }

    @Test fun slowChargeWithoutReasonBlamesCharger() {
        val d = ChargeDiagnostics.diagnose(9f, 40, 30f, screenOn = false, plugType = 1)
        assertEquals(listOf(ChargeLimiter.WEAK_CHARGER), d.limiters)
    }

    @Test fun explicitReasonsAreReportedInsteadOfCharger() {
        val d = ChargeDiagnostics.diagnose(9f, 85, 41f, screenOn = true, plugType = 1)
        assertEquals(listOf(ChargeLimiter.CV_PHASE, ChargeLimiter.HOT, ChargeLimiter.SCREEN_ON), d.limiters)
    }
}
