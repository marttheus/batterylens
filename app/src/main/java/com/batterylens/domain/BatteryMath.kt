package com.batterylens.domain

import kotlin.math.abs
import kotlin.math.exp

/** How the raw `BATTERY_PROPERTY_CURRENT_NOW` value should be interpreted. */
enum class CurrentUnit { AUTO, MICROAMPS, MILLIAMPS }

/**
 * Pure battery calculations. No Android dependencies, so everything here is unit-tested on the JVM.
 *
 * Sign convention used across the app: current is in mA, **positive = flowing into the battery**
 * (charging), negative = discharging.
 */
object BatteryMath {

    /** Charge sessions with a smaller level gain are too noisy to estimate capacity from. */
    const val MIN_LEVEL_DELTA_FOR_CAPACITY = 15

    /** Above this level a Li-ion charger switches to constant voltage and current tapers off. */
    const val CV_PHASE_LEVEL = 80

    /** Extra time factor applied to the 80–100 % portion when it is estimated from a CC-phase current. */
    const val CV_PHASE_TIME_FACTOR = 1.6

    /**
     * Steepness of the wear model. Stress per 1 % of charge grows exponentially with the state of
     * charge, which reflects the well-known fact that time spent at high voltage ages Li-ion cells
     * faster. This is an original approximation, not a measured cell model.
     */
    const val WEAR_STEEPNESS = 3.0

    private val stressNormalizer: Double =
        (1..100).sumOf { exp(WEAR_STEEPNESS * it / 100.0) } / 100.0

    /** Relative stress of charging through [level] %. Averages to 1.0 across 1..100. */
    fun stressAt(level: Int): Double =
        exp(WEAR_STEEPNESS * level.coerceIn(1, 100) / 100.0) / stressNormalizer

    /**
     * Wear, in "equivalent full cycles", caused by charging from [fromLevel] to [toLevel].
     * A full 0→100 % charge equals exactly 1.0 cycle; 20→80 % is noticeably less than 0.6.
     */
    fun wearCycles(fromLevel: Int, toLevel: Int): Double {
        val from = fromLevel.coerceIn(0, 100)
        val to = toLevel.coerceIn(0, 100)
        if (to <= from) return 0.0
        return ((from + 1)..to).sumOf { stressAt(it) } / 100.0
    }

    /** Temperature at or below which charging adds no extra thermal stress in the model. */
    const val WEAR_REFERENCE_TEMP_C = 25f

    /**
     * Thermal multiplier for wear: Li-ion ageing roughly doubles for every 10 °C above room
     * temperature. Capped at 4× (45 °C) so a bad sensor reading cannot explode the total.
     */
    fun temperatureFactor(tempC: Float): Double {
        val excess = ((tempC - WEAR_REFERENCE_TEMP_C) / 10f).coerceIn(0f, 2f)
        return Math.pow(2.0, excess.toDouble())
    }

    /** Converts cycles into a share of the rated cycle life (e.g. 500 cycles to 80 % capacity). */
    fun wearPercentOfLife(cycles: Double, ratedCycles: Int): Double =
        if (ratedCycles <= 0) 0.0 else cycles / ratedCycles * 100.0

    /** Trapezoidal integration of two current readings over [dtMs]. Returns mAh (always ≥ 0). */
    fun integrateMah(currentA: Float, currentB: Float, dtMs: Long): Double {
        if (dtMs <= 0) return 0.0
        return (abs(currentA) + abs(currentB)) / 2.0 * dtMs / 3_600_000.0
    }

    /**
     * Picks the charge moved during an interval from the two available sources.
     *
     * The fuel-gauge charge counter is exact across deep-sleep gaps but on many devices only
     * updates in coarse steps; current integration is smooth but blind while the CPU sleeps.
     * We trust the counter for long gaps, or when it agrees with integration, and otherwise
     * fall back to integration.
     */
    fun chooseMah(integratedMah: Double?, counterMah: Double?, dtMs: Long): Double {
        val counterPlausible = counterMah != null && counterMah > 0 && dtMs > 0 &&
            counterMah / (dtMs / 3_600_000.0) < 15_000.0
        if (counterPlausible && dtMs > 5 * 60_000L) return counterMah!!
        if (counterPlausible && integratedMah != null && integratedMah > 0) {
            val ratio = counterMah!! / integratedMah
            if (ratio in 0.33..3.0) return counterMah
        }
        return integratedMah ?: if (counterPlausible) counterMah!! else 0.0
    }

    /** Capacity implied by moving [mah] while the level changed by [levelDelta] %. */
    fun estimateCapacity(mah: Double, levelDelta: Int): Double? {
        if (levelDelta < MIN_LEVEL_DELTA_FOR_CAPACITY || mah <= 0) return null
        return mah * 100.0 / levelDelta
    }

    /** Average of capacity estimates, weighted by the level span each one was measured over. */
    fun weightedCapacity(estimates: List<Pair<Double, Int>>): Double? {
        val valid = estimates.filter { it.first > 0 && it.second > 0 }
        val weight = valid.sumOf { it.second }
        if (weight == 0) return null
        return valid.sumOf { it.first * it.second } / weight
    }

    /**
     * Normalizes a raw current reading to mA with the app sign convention.
     *
     * In AUTO mode, values with magnitude ≥ 10 000 are treated as µA (10 A in mA is not plausible
     * for a phone) and the sign is taken from the plug state, because vendors disagree on it.
     * Returns null when the device does not report current.
     */
    fun normalizeCurrent(raw: Int, unit: CurrentUnit, invert: Boolean, pluggedIn: Boolean): Float? {
        if (raw == Int.MIN_VALUE || raw == Int.MAX_VALUE || raw == 0) return null
        val magnitudeMa = when (unit) {
            CurrentUnit.MICROAMPS -> abs(raw) / 1000f
            CurrentUnit.MILLIAMPS -> abs(raw).toFloat()
            CurrentUnit.AUTO -> if (abs(raw) >= 10_000) abs(raw) / 1000f else abs(raw).toFloat()
        }
        return when (unit) {
            CurrentUnit.AUTO -> if (pluggedIn) magnitudeMa else -magnitudeMa
            else -> {
                val signed = if (raw < 0) -magnitudeMa else magnitudeMa
                if (invert) -signed else signed
            }
        }
    }

    /**
     * Minutes to charge from [fromLevel] to [toLevel] at [currentMa] for a battery of [capacityMah].
     * The part above [CV_PHASE_LEVEL] is stretched when the current was measured below it.
     */
    fun minutesToReach(fromLevel: Int, toLevel: Int, capacityMah: Double, currentMa: Float): Double? {
        if (toLevel <= fromLevel) return 0.0
        if (currentMa <= 0f || capacityMah <= 0) return null
        val minutesPerPercent = capacityMah / 100.0 / currentMa * 60.0
        val ccEnd = minOf(toLevel, CV_PHASE_LEVEL)
        val ccPart = (ccEnd - fromLevel).coerceAtLeast(0)
        val cvPart = toLevel - maxOf(fromLevel, CV_PHASE_LEVEL)
        val cvFactor = if (fromLevel < CV_PHASE_LEVEL) CV_PHASE_TIME_FACTOR else 1.0
        return minutesPerPercent * (ccPart + cvPart.coerceAtLeast(0) * cvFactor)
    }

    /** Minutes until empty when draining [drainMa] from [level] % of [capacityMah]. */
    fun minutesUntilEmpty(level: Int, capacityMah: Double, drainMa: Double?): Double? {
        if (drainMa == null || drainMa <= 0 || capacityMah <= 0) return null
        return level.coerceIn(0, 100) / 100.0 * capacityMah / drainMa * 60.0
    }

    /** Average current (mA) over a period, or null when the period is too short to be meaningful. */
    fun averageMa(mah: Double, ms: Long, minMs: Long = 60_000): Double? =
        if (ms < minMs) null else mah / (ms / 3_600_000.0)

    /** Average drain expressed as % of capacity per hour. */
    fun percentPerHour(avgMa: Double?, capacityMah: Double): Double? =
        if (avgMa == null || capacityMah <= 0) null else avgMa / capacityMah * 100.0

    /** Share of screen-off time the CPU spent in deep sleep (0..100). */
    fun deepSleepPercent(awakeMs: Long, realMs: Long): Double? {
        if (realMs < 60_000) return null
        return ((1.0 - awakeMs.toDouble() / realMs) * 100.0).coerceIn(0.0, 100.0)
    }
}
