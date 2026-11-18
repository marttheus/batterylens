package com.batterylens.domain

import com.batterylens.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    // --- temperature wear factor ---

    @Test fun temperatureFactorDoublesEveryTenDegreesAndIsCapped() {
        assertEquals(1.0, BatteryMath.temperatureFactor(20f), 1e-9)
        assertEquals(1.0, BatteryMath.temperatureFactor(25f), 1e-9)
        assertEquals(2.0, BatteryMath.temperatureFactor(35f), 1e-9)
        assertEquals(4.0, BatteryMath.temperatureFactor(60f), 1e-9)
    }

    // --- firmware charge limit ---

    @Test fun protectionDetectedWhenPluggedAndNotChargingBelowFull() {
        assertEquals(85, ChargeDiagnostics.detectProtection(pluggedIn = true, status = 4, level = 85))
        assertEquals(80, ChargeDiagnostics.detectProtection(pluggedIn = true, status = 5, level = 80))
        assertNull(ChargeDiagnostics.detectProtection(pluggedIn = true, status = 2, level = 85))
        assertNull(ChargeDiagnostics.detectProtection(pluggedIn = false, status = 4, level = 85))
        assertNull(ChargeDiagnostics.detectProtection(pluggedIn = true, status = 5, level = 100))
    }

    @Test fun protectionOverridesOtherDiagnoses() {
        val d = ChargeDiagnostics.diagnose(0.5f, 85, 41f, screenOn = true, plugType = 1, protectionLevel = 85)
        assertEquals(listOf(ChargeLimiter.PROTECTION), d.limiters)
    }

    // --- charge speed log ---

    @Test fun detectsDropWithCauseAndRecovery() {
        val det = ChargeEventDetector()
        var t = 0L
        repeat(30) { assertNull(det.feed(t, 38f, 40, 32f, false, 1)); t += 10_000 }
        var drop: ChargeEvent? = null
        repeat(5) { if (drop == null) drop = det.feed(t, 12f, 41, 41f, false, 1); t += 10_000 }
        assertNotNull(drop)
        assertEquals(ChargeEventType.DROP, drop!!.type)
        assertEquals(38f, drop!!.fromW, 0.01f)
        assertEquals(12f, drop!!.toW, 0.01f)
        assertEquals(ChargeLimiter.HOT, drop!!.cause)

        var recovery: ChargeEvent? = null
        repeat(10) { if (recovery == null) recovery = det.feed(t, 38f, 45, 33f, false, 1); t += 10_000 }
        assertEquals(ChargeEventType.RECOVERY, recovery?.type)
    }

    @Test fun ignoresTaperAbove80AndSmallNoise() {
        val det = ChargeEventDetector()
        var t = 0L
        repeat(30) { det.feed(t, 30f, 70, 30f, false, 1); t += 10_000 }
        repeat(10) { assertNull(det.feed(t, 26f, 72, 30f, false, 1)); t += 10_000 }
        repeat(10) { assertNull(det.feed(t, 8f, 85, 30f, false, 1)); t += 10_000 }
    }

    // --- abnormal drain ---

    private fun discharge(id: Long, offMs: Long, offMah: Double, realMs: Long, awakeMs: Long) = SessionEntity(
        id = id, type = SessionEntity.TYPE_DISCHARGE, startTime = 0, updatedAt = 0,
        startLevel = 90, endLevel = 80, screenOffMs = offMs, screenOffMah = offMah,
        screenOffRealMs = realMs, screenOffAwakeMs = awakeMs,
    )

    @Test fun drainAlertFiresOncePerScreenOffStretch() {
        val w = DrainWatcher(minWindowMs = 60 * 60_000L)
        val hour = 3_600_000L
        assertNull(w.update(discharge(1, 0, 0.0, 0, 0), false, 4000.0, 3.0, 60.0))
        // 1 h screen off, 200 mAh = 5 %/h, deep sleep 50 %
        val alert = w.update(discharge(1, hour, 200.0, hour, hour / 2), false, 4000.0, 3.0, 60.0)
        assertNotNull(alert)
        assertEquals(5.0, alert!!.pctPerHour!!, 1e-9)
        assertEquals(50.0, alert.deepSleepPct!!, 1e-9)
        assertNull(w.update(discharge(1, 2 * hour, 400.0, 2 * hour, hour), false, 4000.0, 3.0, 60.0))
        // Screen on resets the stretch.
        assertNull(w.update(discharge(1, 2 * hour, 400.0, 2 * hour, hour), true, 4000.0, 3.0, 60.0))
    }

    @Test fun healthyNightDoesNotAlert() {
        val w = DrainWatcher(minWindowMs = 60 * 60_000L)
        val hour = 3_600_000L
        w.update(discharge(1, 0, 0.0, 0, 0), false, 4000.0, 3.0, 60.0)
        assertNull(w.update(discharge(1, 8 * hour, 160.0, 8 * hour, hour / 2), false, 4000.0, 3.0, 60.0))
    }

    // --- habits ---

    private fun charge(start: Int, end: Int, startTime: Long, fullHoldMs: Long = 0) = SessionEntity(
        type = SessionEntity.TYPE_CHARGE, startTime = startTime, updatedAt = startTime,
        startLevel = start, endLevel = end, fullHoldMs = fullHoldMs,
    )

    @Test fun habitsSummariseChargeSessions() {
        val day = 86_400_000L
        val sessions = listOf(
            charge(15, 100, 0, fullHoldMs = 3_600_000),
            charge(30, 80, day),
            charge(40, 90, 2 * day),
            charge(50, 60, 3 * day),
        )
        val h = Habits.compute(sessions, sinceMs = 0, nowMs = 4 * day)!!
        assertEquals(4, h.chargeCount)
        assertEquals(1.0, h.chargesPerDay, 1e-9)
        assertEquals(33.75, h.avgStartLevel, 1e-9)
        assertEquals(50.0, h.shareAbove80Pct, 1e-9)
        assertEquals(25.0, h.shareBelow20StartPct, 1e-9)
        assertEquals(3_600_000L, h.fullHoldMs)
        assertNull(Habits.compute(emptyList(), 0, day))
    }

    // --- charger comparison ---

    @Test fun chargerComparisonRanksByAveragePowerBelow80() {
        val fast = SessionEntity(
            type = SessionEntity.TYPE_CHARGE, startTime = 0, updatedAt = 0, startLevel = 20, endLevel = 80,
            peakPowerW = 40f, energyBelow80Wh = 15.0, msBelow80 = 30 * 60_000L,
        )
        val slow = fast.copy(peakPowerW = 10f, energyBelow80Wh = 9.0, msBelow80 = 60 * 60_000L)
        val stats = ChargerComparison.compute(listOf(1L to slow, 2L to fast), 5000.0)
        assertEquals(2L, stats.first().chargerId)
        assertEquals(30.0, stats.first().avgPowerW!!, 1e-9)
        // 0.6 × 5000 mAh × 3.87 V = 11.61 Wh at 30 W → 23.2 min
        assertEquals(23.22, stats.first().minutes20to80!!, 0.01)
        assertEquals(2L, ChargerComparison.guess(37f, stats))
        assertNull(ChargerComparison.guess(25f, stats))
        assertTrue(stats.size == 2)
    }
}
