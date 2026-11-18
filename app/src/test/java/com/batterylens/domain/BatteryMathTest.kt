package com.batterylens.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMathTest {

    @Test fun fullChargeIsOneCycle() {
        assertEquals(1.0, BatteryMath.wearCycles(0, 100), 1e-9)
    }

    @Test fun chargingTo80WearsLessThanProportionally() {
        val partial = BatteryMath.wearCycles(20, 80)
        assertTrue("20→80 should be < 0.6 cycles, was $partial", partial < 0.6)
        // The last 20 % costs more than the 20 % from 20 to 40.
        assertTrue(BatteryMath.wearCycles(80, 100) > BatteryMath.wearCycles(20, 40))
    }

    @Test fun noWearWhenLevelDoesNotRise() {
        assertEquals(0.0, BatteryMath.wearCycles(60, 60), 0.0)
        assertEquals(0.0, BatteryMath.wearCycles(60, 40), 0.0)
    }

    @Test fun wearIsAdditiveAcrossSteps() {
        val stepwise = BatteryMath.wearCycles(30, 55) + BatteryMath.wearCycles(55, 90)
        assertEquals(BatteryMath.wearCycles(30, 90), stepwise, 1e-9)
    }

    @Test fun integrationUsesMagnitudes() {
        // 1000 mA for one hour = 1000 mAh regardless of sign.
        assertEquals(1000.0, BatteryMath.integrateMah(-1000f, -1000f, 3_600_000), 1e-9)
        assertEquals(750.0, BatteryMath.integrateMah(500f, 1000f, 3_600_000), 1e-9)
    }

    @Test fun capacityNeedsEnoughLevelDelta() {
        assertNull(BatteryMath.estimateCapacity(500.0, 10))
        assertEquals(4000.0, BatteryMath.estimateCapacity(2000.0, 50)!!, 1e-9)
    }

    @Test fun weightedCapacityFavoursLongerSessions() {
        val cap = BatteryMath.weightedCapacity(listOf(4000.0 to 60, 3000.0 to 20))!!
        assertEquals(3750.0, cap, 1e-9)
        assertNull(BatteryMath.weightedCapacity(emptyList()))
    }

    @Test fun autoCurrentDetectsMicroampsAndSign() {
        assertEquals(1500f, BatteryMath.normalizeCurrent(1_500_000, CurrentUnit.AUTO, false, true)!!, 0.01f)
        assertEquals(-1500f, BatteryMath.normalizeCurrent(1_500_000, CurrentUnit.AUTO, false, false)!!, 0.01f)
        assertEquals(-420f, BatteryMath.normalizeCurrent(420, CurrentUnit.AUTO, false, false)!!, 0.01f)
        assertNull(BatteryMath.normalizeCurrent(Int.MIN_VALUE, CurrentUnit.AUTO, false, false))
    }

    @Test fun explicitCurrentUnitHonoursInvert() {
        assertEquals(-300f, BatteryMath.normalizeCurrent(300_000, CurrentUnit.MICROAMPS, true, true)!!, 0.01f)
        assertEquals(300f, BatteryMath.normalizeCurrent(-300, CurrentUnit.MILLIAMPS, true, false)!!, 0.01f)
    }

    @Test fun chargeTimeStretchesConstantVoltagePhase() {
        // 4000 mAh at 2000 mA: 1 % = 1.2 min.
        val toEighty = BatteryMath.minutesToReach(50, 80, 4000.0, 2000f)!!
        assertEquals(36.0, toEighty, 1e-9)
        val toFull = BatteryMath.minutesToReach(50, 100, 4000.0, 2000f)!!
        assertEquals(36.0 + 20 * 1.2 * BatteryMath.CV_PHASE_TIME_FACTOR, toFull, 1e-9)
        // Already in CV phase: measured current already reflects the taper.
        assertEquals(12.0, BatteryMath.minutesToReach(90, 100, 4000.0, 2000f)!!, 1e-9)
        assertNull(BatteryMath.minutesToReach(50, 80, 4000.0, -100f))
    }

    @Test fun counterPreferredForLongGapsOrWhenConsistent() {
        assertEquals(50.0, BatteryMath.chooseMah(2.0, 50.0, 30 * 60_000L), 1e-9)
        assertEquals(1.1, BatteryMath.chooseMah(1.0, 1.1, 10_000L), 1e-9)
        // Coarse counter step that disagrees with integration over a short tick → integration.
        assertEquals(1.0, BatteryMath.chooseMah(1.0, 40.0, 10_000L), 1e-9)
        assertEquals(0.0, BatteryMath.chooseMah(null, null, 10_000L), 0.0)
    }

    @Test fun deepSleepAndRates() {
        assertEquals(90.0, BatteryMath.deepSleepPercent(360_000, 3_600_000)!!, 1e-9)
        assertNull(BatteryMath.deepSleepPercent(0, 10_000))
        val avg = BatteryMath.averageMa(200.0, 3_600_000)!!
        assertEquals(5.0, BatteryMath.percentPerHour(avg, 4000.0)!!, 1e-9)
        assertEquals(600.0, BatteryMath.minutesUntilEmpty(50, 4000.0, 200.0)!!, 1e-9)
    }
}
