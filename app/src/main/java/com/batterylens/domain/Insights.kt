package com.batterylens.domain

import com.batterylens.data.SessionEntity
import com.batterylens.data.isCharge
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Charge speed log
// ---------------------------------------------------------------------------------------------

enum class ChargeEventType { DROP, RECOVERY }

data class ChargeEvent(
    val timestamp: Long,
    val type: ChargeEventType,
    val fromW: Float,
    val toW: Float,
    val level: Int,
    val temperatureC: Float,
    /** Most likely cause at the moment of a drop; null when nothing explains it. */
    val cause: ChargeLimiter?,
)

/**
 * Watches charging power and reports sudden drops (and their recovery) below the CV phase,
 * where power is expected to be steady. Power is smoothed with an EMA so a single noisy reading
 * does not trigger; the reported "to" value is the raw reading that crossed the threshold.
 */
class ChargeEventDetector(
    private val dropRatio: Float = 0.6f,
    private val recoveryRatio: Float = 0.85f,
    private val minBaselineW: Float = 5f,
    private val windowMs: Long = 5 * 60_000L,
    private val cooldownMs: Long = 2 * 60_000L,
    private val alpha: Float = 0.4f,
) {
    private var ema: Float? = null
    private val history = ArrayDeque<Pair<Long, Float>>()
    private var droppedFrom: Float? = null
    private var lastEventAt = Long.MIN_VALUE / 2

    fun reset() {
        ema = null
        history.clear()
        droppedFrom = null
        lastEventAt = Long.MIN_VALUE / 2
    }

    fun feed(
        timestamp: Long,
        powerW: Float?,
        level: Int,
        temperatureC: Float,
        screenOn: Boolean,
        plugType: Int,
    ): ChargeEvent? {
        if (powerW == null || level >= BatteryMath.CV_PHASE_LEVEL) return null
        val raw = abs(powerW)
        val smoothed = ema?.let { it + alpha * (raw - it) } ?: raw
        ema = smoothed
        history.addLast(timestamp to smoothed)
        while (history.isNotEmpty() && timestamp - history.first().first > windowMs) history.removeFirst()
        val baseline = history.maxOf { it.second }

        val dropped = droppedFrom
        if (dropped == null) {
            if (baseline >= minBaselineW && smoothed <= baseline * dropRatio && timestamp - lastEventAt > cooldownMs) {
                droppedFrom = baseline
                lastEventAt = timestamp
                history.clear()
                history.addLast(timestamp to smoothed)
                val cause = ChargeDiagnostics.diagnose(raw, level, temperatureC, screenOn, plugType)
                    .limiters.firstOrNull { it != ChargeLimiter.WEAK_CHARGER }
                return ChargeEvent(timestamp, ChargeEventType.DROP, baseline, raw, level, temperatureC, cause)
            }
        } else if (smoothed >= dropped * recoveryRatio) {
            droppedFrom = null
            lastEventAt = timestamp
            return ChargeEvent(timestamp, ChargeEventType.RECOVERY, history.minOf { it.second }, raw, level, temperatureC, null)
        }
        return null
    }
}

// ---------------------------------------------------------------------------------------------
// Abnormal screen-off drain
// ---------------------------------------------------------------------------------------------

data class DrainAlert(val pctPerHour: Double?, val deepSleepPct: Double?, val windowMs: Long)

/**
 * Raises one alert per screen-off stretch when, after [minWindowMs] with the screen off, the drain
 * is above the limit or the CPU barely deep-slept — the signature of an app keeping the phone awake.
 */
class DrainWatcher(private val minWindowMs: Long = 60 * 60_000L) {
    private data class Anchor(val sessionId: Long, val mah: Double, val ms: Long, val realMs: Long, val awakeMs: Long)

    private var anchor: Anchor? = null
    private var alerted = false

    fun update(
        session: SessionEntity?,
        screenOn: Boolean,
        capacityMah: Double,
        maxPctPerHour: Double,
        minDeepSleepPct: Double,
    ): DrainAlert? {
        if (session == null || session.isCharge || screenOn) {
            anchor = null
            alerted = false
            return null
        }
        val a = anchor
        if (a == null || a.sessionId != session.id) {
            anchor = Anchor(session.id, session.screenOffMah, session.screenOffMs, session.screenOffRealMs, session.screenOffAwakeMs)
            alerted = false
            return null
        }
        val dMs = session.screenOffMs - a.ms
        if (alerted || dMs < minWindowMs) return null
        val rate = BatteryMath.percentPerHour(BatteryMath.averageMa(session.screenOffMah - a.mah, dMs), capacityMah)
        val deep = BatteryMath.deepSleepPercent(session.screenOffAwakeMs - a.awakeMs, session.screenOffRealMs - a.realMs)
        val tooFast = rate != null && rate > maxPctPerHour
        val insomnia = deep != null && deep < minDeepSleepPct
        if (!tooFast && !insomnia) return null
        alerted = true
        return DrainAlert(rate, deep, dMs)
    }
}

// ---------------------------------------------------------------------------------------------
// Charging habits
// ---------------------------------------------------------------------------------------------

data class HabitStats(
    val chargeCount: Int,
    val chargesPerDay: Double,
    val avgStartLevel: Double,
    val avgEndLevel: Double,
    val shareAbove80Pct: Double,
    val shareBelow20StartPct: Double,
    val fullHoldMs: Long,
    val protectedHoldMs: Long,
)

object Habits {
    fun compute(sessions: List<SessionEntity>, sinceMs: Long, nowMs: Long): HabitStats? {
        val charges = sessions.filter { it.isCharge && it.startTime >= sinceMs && it.endLevel > it.startLevel }
        if (charges.isEmpty()) return null
        val days = ((nowMs - maxOf(sinceMs, charges.minOf { it.startTime })) / 86_400_000.0).coerceAtLeast(1.0)
        return HabitStats(
            chargeCount = charges.size,
            chargesPerDay = charges.size / days,
            avgStartLevel = charges.map { it.startLevel }.average(),
            avgEndLevel = charges.map { it.endLevel }.average(),
            shareAbove80Pct = charges.count { it.endLevel > 80 } * 100.0 / charges.size,
            shareBelow20StartPct = charges.count { it.startLevel < 20 } * 100.0 / charges.size,
            fullHoldMs = sessions.filter { it.isCharge && it.startTime >= sinceMs }.sumOf { it.fullHoldMs },
            protectedHoldMs = sessions.filter { it.isCharge && it.startTime >= sinceMs }.sumOf { it.protectedHoldMs },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Charger comparison
// ---------------------------------------------------------------------------------------------

data class ChargerStats(
    val chargerId: Long,
    val sessions: Int,
    val bestPeakW: Float,
    /** Average power while below 80 %, i.e. the part of the charge the charger controls. */
    val avgPowerW: Double?,
    val minutes20to80: Double?,
)

object ChargerComparison {
    /** Typical Li-ion nominal voltage, used to turn the 20→80 % capacity span into energy. */
    const val NOMINAL_VOLTAGE = 3.87

    fun compute(rows: List<Pair<Long, SessionEntity>>, capacityMah: Double): List<ChargerStats> =
        rows.groupBy({ it.first }, { it.second }).map { (id, list) ->
            val measured = list.filter { it.msBelow80 >= 5 * 60_000L && it.energyBelow80Wh > 0 }
            val hours = measured.sumOf { it.msBelow80 } / 3_600_000.0
            val avgW = if (hours > 0) measured.sumOf { it.energyBelow80Wh } / hours else null
            val energy20to80 = 0.6 * capacityMah * NOMINAL_VOLTAGE / 1000.0
            ChargerStats(
                chargerId = id,
                sessions = list.size,
                bestPeakW = list.maxOf { it.peakPowerW },
                avgPowerW = avgW,
                minutes20to80 = avgW?.takeIf { it > 0 }?.let { energy20to80 / it * 60.0 },
            )
        }.sortedByDescending { it.avgPowerW ?: 0.0 }

    /** Closest charger whose best peak is within 15 % of [peakW], or null. */
    fun guess(peakW: Float, stats: List<ChargerStats>): Long? {
        if (peakW <= 0f) return null
        return stats
            .filter { it.bestPeakW > 0f && abs(it.bestPeakW - peakW) / it.bestPeakW <= 0.15f }
            .minByOrNull { abs(it.bestPeakW - peakW) }
            ?.chargerId
    }
}
