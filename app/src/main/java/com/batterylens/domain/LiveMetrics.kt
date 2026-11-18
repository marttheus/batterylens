package com.batterylens.domain

import com.batterylens.battery.BatterySnapshot
import com.batterylens.data.SessionEntity
import com.batterylens.data.isCharge
import kotlin.math.abs

/** Everything the UI and notification derive from the latest snapshot + open session. */
data class LiveMetrics(
    val currentMa: Float?,
    val powerW: Float?,
    val minutesToTarget: Double?,
    val minutesToFull: Double?,
    val sessionAvgChargeMa: Double?,
    val screenOnAvgMa: Double?,
    val screenOffAvgMa: Double?,
    val combinedAvgMa: Double?,
    val screenOnPctPerHour: Double?,
    val screenOffPctPerHour: Double?,
    val remainingScreenOnMin: Double?,
    val remainingScreenOffMin: Double?,
    val remainingCombinedMin: Double?,
    val deepSleepPct: Double?,
)

object LiveMetricsCalculator {

    fun compute(
        snap: BatterySnapshot,
        session: SessionEntity?,
        capacityMah: Double,
        chargeTarget: Int,
        screenOn: Boolean,
    ): LiveMetrics {
        val charging = session?.isCharge ?: snap.pluggedIn
        val chargeMs = session?.let { it.screenOnMs + it.screenOffMs } ?: 0
        val sessionAvgCharge = if (charging && session != null) BatteryMath.averageMa(session.totalMah, chargeMs) else null

        // Instant current is the best predictor while charging; fall back to the session mean.
        val chargeRate = snap.currentMa?.takeIf { charging && it > 30f } ?: sessionAvgCharge?.toFloat()
        val toTarget = chargeRate?.let { BatteryMath.minutesToReach(snap.level, chargeTarget, capacityMah, it) }
        val toFull = chargeRate?.let { BatteryMath.minutesToReach(snap.level, 100, capacityMah, it) }

        val dischargeSession = session?.takeIf { !it.isCharge }
        val instantDrain = snap.currentMa?.takeIf { !charging && it < 0 }?.let { abs(it).toDouble() }
        val onAvg = dischargeSession?.let { BatteryMath.averageMa(it.screenOnMah, it.screenOnMs) }
            ?: instantDrain?.takeIf { screenOn }
        val offAvg = dischargeSession?.let { BatteryMath.averageMa(it.screenOffMah, it.screenOffMs) }
            ?: instantDrain?.takeIf { !screenOn }
        val combined = dischargeSession?.let {
            BatteryMath.averageMa(it.screenOnMah + it.screenOffMah, it.screenOnMs + it.screenOffMs)
        } ?: instantDrain

        return LiveMetrics(
            currentMa = snap.currentMa,
            powerW = snap.powerW,
            minutesToTarget = if (charging) toTarget else null,
            minutesToFull = if (charging) toFull else null,
            sessionAvgChargeMa = sessionAvgCharge,
            screenOnAvgMa = onAvg,
            screenOffAvgMa = offAvg,
            combinedAvgMa = combined,
            screenOnPctPerHour = BatteryMath.percentPerHour(onAvg, capacityMah),
            screenOffPctPerHour = BatteryMath.percentPerHour(offAvg, capacityMah),
            remainingScreenOnMin = BatteryMath.minutesUntilEmpty(snap.level, capacityMah, onAvg),
            remainingScreenOffMin = BatteryMath.minutesUntilEmpty(snap.level, capacityMah, offAvg),
            remainingCombinedMin = BatteryMath.minutesUntilEmpty(snap.level, capacityMah, combined),
            deepSleepPct = dischargeSession?.let {
                BatteryMath.deepSleepPercent(it.screenOffAwakeMs, it.screenOffRealMs)
            },
        )
    }

    /** Capacity used for estimates: measured health first, then design, then a generic default. */
    fun effectiveCapacity(estimatedMah: Double?, designMah: Double?): Double =
        estimatedMah ?: designMah ?: DEFAULT_CAPACITY_MAH

    const val DEFAULT_CAPACITY_MAH = 4000.0
}
