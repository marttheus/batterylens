package com.batterylens.data

import android.content.Context
import androidx.room.Dao
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
}

@Database(
    entities = [SessionEntity::class, SampleEntity::class, AppUsageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BatteryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "batterylens.db").build()
    }
}
