package com.batterylens.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A charge session (plugged in) or discharge session (on battery). A session is "open" while
 * [endTime] is null; the tracker updates it on every sample so it survives process death.
 */
@Entity(tableName = "sessions", indices = [Index("startTime")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val startTime: Long,
    val updatedAt: Long,
    val endTime: Long? = null,
    val startLevel: Int,
    val endLevel: Int,
    val plugType: Int = 0,
    /** Charge moved into (charge session) or out of (discharge session) the battery. */
    val totalMah: Double = 0.0,
    val screenOnMs: Long = 0,
    val screenOffMs: Long = 0,
    val screenOnMah: Double = 0.0,
    val screenOffMah: Double = 0.0,
    val screenOnLevelDrop: Int = 0,
    val screenOffLevelDrop: Int = 0,
    /** Wall-clock and CPU-awake time while the screen was off, for deep-sleep ratio. */
    val screenOffRealMs: Long = 0,
    val screenOffAwakeMs: Long = 0,
    val wearCycles: Double = 0.0,
    val estimatedCapacityMah: Double? = null,
    val maxTempC: Float = 0f,
    val peakCurrentMa: Float = 0f,
    // --- v2 ---
    @ColumnInfo(defaultValue = "0") val peakPowerW: Float = 0f,
    /** Energy moved, in Wh (mAh × V), overall and while the level was below 80 %. */
    @ColumnInfo(defaultValue = "0") val energyWh: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val energyBelow80Wh: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val msBelow80: Long = 0,
    /** Time spent plugged in at 100 %, and held below 100 % by a firmware charge limit. */
    @ColumnInfo(defaultValue = "0") val fullHoldMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val protectedHoldMs: Long = 0,
    /** Level a firmware charge limit held the battery at, if one was detected. */
    val protectionLevel: Int? = null,
) {
    companion object {
        const val TYPE_CHARGE = "CHARGE"
        const val TYPE_DISCHARGE = "DISCHARGE"
    }
}

val SessionEntity.isCharge: Boolean get() = type == SessionEntity.TYPE_CHARGE
val SessionEntity.isOpen: Boolean get() = endTime == null
val SessionEntity.durationMs: Long get() = (endTime ?: updatedAt) - startTime

@Entity(
    tableName = "samples",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val level: Int,
    val currentMa: Float?,
    val voltageMv: Int,
    val temperatureC: Float,
    val screenOn: Boolean,
    val plugged: Int,
)

/** Estimated energy use of a foreground app during a discharge session (screen on only). */
@Entity(
    tableName = "app_usage",
    primaryKeys = ["sessionId", "packageName"],
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AppUsageEntity(
    val sessionId: Long,
    val packageName: String,
    val foregroundMs: Long = 0,
    val mah: Double = 0.0,
)

/** A charger/cable combination the user named, to compare charge speeds. */
@Entity(tableName = "chargers")
data class ChargerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * Charger assigned to a charge session. Kept in its own table so the tracker, which rewrites the
 * whole session row on every tick, can never overwrite the user's choice.
 */
@Entity(
    tableName = "session_chargers",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChargerEntity::class, parentColumns = ["id"], childColumns = ["chargerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("chargerId")],
)
data class SessionChargerEntity(
    @PrimaryKey val sessionId: Long,
    val chargerId: Long,
)

/** A sudden drop (or recovery) of charging power, see ChargeEventDetector. */
@Entity(
    tableName = "charge_events",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class ChargeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val type: String,
    val fromW: Float,
    val toW: Float,
    val level: Int,
    val temperatureC: Float,
    val cause: String?,
)

data class ChargerSessionRow(
    @Embedded val session: SessionEntity,
    val chargerId: Long,
)

/** Aggregated screen-on/off drain over several discharge sessions. */
data class DrainTotals(
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnMah: Double,
    val screenOffMah: Double,
)

@Dao
interface BatteryDao {
    @Insert suspend fun insertSession(session: SessionEntity): Long
    @Update suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startTime ASC")
    suspend fun allSessions(): List<SessionEntity>

    @Query(
        "SELECT * FROM sessions WHERE type = 'CHARGE' AND estimatedCapacityMah IS NOT NULL " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    fun observeCapacityEstimates(limit: Int): Flow<List<SessionEntity>>

    @Query(
        "SELECT * FROM sessions WHERE type = 'CHARGE' AND estimatedCapacityMah IS NOT NULL " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    suspend fun capacityEstimates(limit: Int): List<SessionEntity>

    @Query(
        "SELECT COALESCE(SUM(screenOnMs),0) AS screenOnMs, COALESCE(SUM(screenOffMs),0) AS screenOffMs, " +
            "COALESCE(SUM(screenOnMah),0) AS screenOnMah, COALESCE(SUM(screenOffMah),0) AS screenOffMah " +
            "FROM sessions WHERE type = 'DISCHARGE' AND startTime >= :since"
    )
    fun observeDrainTotalsSince(since: Long): Flow<DrainTotals>

    @Insert suspend fun insertSample(sample: SampleEntity)

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeSamples(sessionId: Long): Flow<List<SampleEntity>>

    @Upsert suspend fun upsertAppUsage(usage: List<AppUsageEntity>)

    @Query("SELECT * FROM app_usage WHERE sessionId = :sessionId")
    suspend fun appUsage(sessionId: Long): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage WHERE sessionId = :sessionId ORDER BY mah DESC")
    fun observeAppUsage(sessionId: Long): Flow<List<AppUsageEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM sessions WHERE endTime IS NOT NULL AND endTime < :before")
    suspend fun deleteClosedBefore(before: Long)

    @Query("DELETE FROM sessions WHERE endTime IS NOT NULL")
    suspend fun deleteAllClosed()

    @Query("SELECT * FROM sessions WHERE protectionLevel IS NOT NULL ORDER BY startTime DESC LIMIT 1")
    fun observeLastProtectedSession(): Flow<SessionEntity?>

    // --- chargers ---
    @Insert suspend fun insertCharger(charger: ChargerEntity): Long
    @Update suspend fun updateCharger(charger: ChargerEntity)
    @Query("DELETE FROM chargers WHERE id = :id") suspend fun deleteCharger(id: Long)

    @Query("SELECT * FROM chargers ORDER BY name COLLATE NOCASE")
    fun observeChargers(): Flow<List<ChargerEntity>>

    @Upsert suspend fun setSessionCharger(link: SessionChargerEntity)

    @Query("DELETE FROM session_chargers WHERE sessionId = :sessionId")
    suspend fun clearSessionCharger(sessionId: Long)

    @Query("SELECT chargerId FROM session_chargers WHERE sessionId = :sessionId")
    fun observeSessionCharger(sessionId: Long): Flow<Long?>

    @Query(
        "SELECT sessions.*, session_chargers.chargerId AS chargerId FROM sessions " +
            "JOIN session_chargers ON session_chargers.sessionId = sessions.id WHERE sessions.type = 'CHARGE'"
    )
    fun observeChargerSessions(): Flow<List<ChargerSessionRow>>

    // --- charge events ---
    @Insert suspend fun insertChargeEvent(event: ChargeEventEntity)

    @Query("SELECT * FROM charge_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeChargeEvents(sessionId: Long): Flow<List<ChargeEventEntity>>
}

@Database(
    entities = [
        SessionEntity::class, SampleEntity::class, AppUsageEntity::class,
        ChargerEntity::class, SessionChargerEntity::class, ChargeEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BatteryDao

    companion object {
        const val FILE_NAME = "batterylens.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME).build()
    }
}
