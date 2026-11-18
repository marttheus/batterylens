package com.batterylens.domain

import com.batterylens.battery.BatterySnapshot
import com.batterylens.data.AppUsageEntity
import com.batterylens.data.BatteryDao
import com.batterylens.data.ChargeEventEntity
import com.batterylens.data.SampleEntity
import com.batterylens.data.SessionEntity
import com.batterylens.data.durationMs
import com.batterylens.data.isCharge
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns a stream of battery snapshots into charge/discharge sessions.
 *
 * Every tick attributes the interval since the previous tick to the *previous* screen state,
 * foreground app and protection state, so callers should tick immediately on screen/plug changes
 * for clean boundaries. Not thread-safe: call from a single-threaded dispatcher.
 */
class SessionTracker(
    private val dao: BatteryDao,
    private val onSessionClosed: suspend (SessionEntity) -> Unit,
) {
    private data class Ref(
        val time: Long,
        val realtime: Long,
        val uptime: Long,
        val level: Int,
        val currentMa: Float?,
        val voltageMv: Int,
        val temperatureC: Float,
        val counterUah: Long?,
        val screenOn: Boolean,
        val foregroundApp: String?,
        val protectedHold: Boolean,
    )

    private var session: SessionEntity? = null
    private var last: Ref? = null
    private val apps = HashMap<String, AppUsageEntity>()
    private val dirtyApps = HashSet<String>()
    private val events = ChargeEventDetector()

    /** Level held by a firmware charge limit on the latest tick, or null. */
    var protectionLevel: Int? = null
        private set

    suspend fun onTick(
        snap: BatterySnapshot,
        screenOn: Boolean,
        foregroundApp: String?,
        realtimeMs: Long,
        uptimeMs: Long,
    ): SessionEntity {
        val type = if (snap.pluggedIn) SessionEntity.TYPE_CHARGE else SessionEntity.TYPE_DISCHARGE
        var s = session ?: resume(type, snap.timestamp)
        if (s != null && s.type != type) {
            finalize(s)
            s = null
        }
        if (s == null) s = open(type, snap)

        val protection = ChargeDiagnostics.detectProtection(snap.pluggedIn, snap.status, snap.level)
        protectionLevel = protection

        var current: SessionEntity = s
        last?.let { prev -> current = accumulate(current, prev, snap, realtimeMs, uptimeMs) }

        current = current.copy(
            endLevel = snap.level,
            updatedAt = snap.timestamp,
            plugType = if (snap.pluggedIn) snap.plugged else 0,
            maxTempC = max(current.maxTempC, snap.temperatureC),
            peakCurrentMa = max(current.peakCurrentMa, abs(snap.currentMa ?: 0f)),
            peakPowerW = max(current.peakPowerW, abs(snap.powerW ?: 0f)),
            protectionLevel = protection ?: current.protectionLevel,
        )
        dao.updateSession(current)
        if (dirtyApps.isNotEmpty()) {
            dao.upsertAppUsage(dirtyApps.mapNotNull { apps[it] })
            dirtyApps.clear()
        }
        dao.insertSample(
            SampleEntity(
                sessionId = current.id,
                timestamp = snap.timestamp,
                level = snap.level,
                currentMa = snap.currentMa,
                voltageMv = snap.voltageMv,
                temperatureC = snap.temperatureC,
                screenOn = screenOn,
                plugged = snap.plugged,
            )
        )
        if (current.isCharge && protection == null) {
            events.feed(snap.timestamp, snap.powerW, snap.level, snap.temperatureC, screenOn, snap.plugged)?.let { e ->
                dao.insertChargeEvent(
                    ChargeEventEntity(
                        sessionId = current.id, timestamp = e.timestamp, type = e.type.name,
                        fromW = e.fromW, toW = e.toW, level = e.level, temperatureC = e.temperatureC, cause = e.cause?.name,
                    )
                )
            }
        }
        session = current
        last = Ref(
            snap.timestamp, realtimeMs, uptimeMs, snap.level, snap.currentMa, snap.voltageMv, snap.temperatureC,
            snap.chargeCounterUah, screenOn, foregroundApp, protection != null,
        )
        return current
    }

    private fun accumulate(
        s: SessionEntity,
        prev: Ref,
        snap: BatterySnapshot,
        realtimeMs: Long,
        uptimeMs: Long,
    ): SessionEntity {
        val dt = snap.timestamp - prev.time
        if (dt <= 0 || dt > MAX_GAP_MS) return s

        val integrated = if (prev.currentMa != null && snap.currentMa != null) {
            BatteryMath.integrateMah(prev.currentMa, snap.currentMa, dt)
        } else null
        val counterMah = if (prev.counterUah != null && snap.chargeCounterUah != null) {
            abs(snap.chargeCounterUah - prev.counterUah) / 1000.0
        } else null
        val mah = BatteryMath.chooseMah(integrated, counterMah, dt)
        val wh = mah * (prev.voltageMv + snap.voltageMv) / 2.0 / 1_000_000.0

        var out = s.copy(totalMah = s.totalMah + mah, energyWh = s.energyWh + wh)
        out = if (prev.screenOn) {
            out.copy(screenOnMs = out.screenOnMs + dt, screenOnMah = out.screenOnMah + mah)
        } else {
            val realDelta = (realtimeMs - prev.realtime).coerceAtLeast(0)
            val awakeDelta = (uptimeMs - prev.uptime).coerceIn(0, realDelta)
            out.copy(
                screenOffMs = out.screenOffMs + dt,
                screenOffMah = out.screenOffMah + mah,
                screenOffRealMs = out.screenOffRealMs + realDelta,
                screenOffAwakeMs = out.screenOffAwakeMs + awakeDelta,
            )
        }

        if (s.isCharge) {
            if (prev.level < BatteryMath.CV_PHASE_LEVEL && !prev.protectedHold) {
                out = out.copy(energyBelow80Wh = out.energyBelow80Wh + wh, msBelow80 = out.msBelow80 + dt)
            }
            if (prev.level >= 100) out = out.copy(fullHoldMs = out.fullHoldMs + dt)
            if (prev.protectedHold) out = out.copy(protectedHoldMs = out.protectedHoldMs + dt)
            if (snap.level > prev.level) {
                val thermal = BatteryMath.temperatureFactor((prev.temperatureC + snap.temperatureC) / 2f)
                out = out.copy(wearCycles = out.wearCycles + BatteryMath.wearCycles(prev.level, snap.level) * thermal)
            }
        } else {
            val drop = prev.level - snap.level
            if (drop > 0) {
                out = if (prev.screenOn) out.copy(screenOnLevelDrop = out.screenOnLevelDrop + drop)
                else out.copy(screenOffLevelDrop = out.screenOffLevelDrop + drop)
            }
            val pkg = prev.foregroundApp
            if (prev.screenOn && pkg != null) {
                val old = apps[pkg] ?: AppUsageEntity(sessionId = s.id, packageName = pkg)
                apps[pkg] = old.copy(foregroundMs = old.foregroundMs + dt, mah = old.mah + mah)
                dirtyApps += pkg
            }
        }
        return out
    }

    private suspend fun resume(type: String, now: Long): SessionEntity? {
        val open = dao.openSession() ?: return null
        if (open.type == type && now - open.updatedAt < RESUME_WINDOW_MS) {
            apps.clear()
            dao.appUsage(open.id).forEach { apps[it.packageName] = it }
            events.reset()
            return open
        }
        finalize(open)
        return null
    }

    private suspend fun open(type: String, snap: BatterySnapshot): SessionEntity {
        apps.clear()
        dirtyApps.clear()
        events.reset()
        last = null
        val fresh = SessionEntity(
            type = type,
            startTime = snap.timestamp,
            updatedAt = snap.timestamp,
            startLevel = snap.level,
            endLevel = snap.level,
            plugType = snap.plugged,
            maxTempC = snap.temperatureC,
        )
        return fresh.copy(id = dao.insertSession(fresh))
    }

    private suspend fun finalize(s: SessionEntity) {
        val capacity = if (s.isCharge) BatteryMath.estimateCapacity(s.totalMah, s.endLevel - s.startLevel) else null
        val closed = s.copy(endTime = s.updatedAt, estimatedCapacityMah = capacity)
        if (closed.durationMs < MIN_SESSION_MS && closed.endLevel == closed.startLevel) {
            dao.deleteSession(closed.id)
        } else {
            dao.updateSession(closed)
            onSessionClosed(closed)
        }
        if (session?.id == s.id) session = null
        last = null
        apps.clear()
        dirtyApps.clear()
    }

    companion object {
        /** Intervals longer than this (e.g. phone switched off) are not attributed to anything. */
        const val MAX_GAP_MS = 6 * 3_600_000L
        /** An open session found after a restart is continued if it was updated this recently. */
        const val RESUME_WINDOW_MS = 15 * 60_000L
        /** Sessions shorter than this with no level change are discarded as plug bounces. */
        const val MIN_SESSION_MS = 60_000L
    }
}
