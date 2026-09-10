package ca.airspacemonitor.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One recorded monitoring event. ENTER/EXIT bracket an episode per
 * (profileId, hex); POSITION rows capture every loop sighting so an episode's
 * path can be reconstructed. Tier is the alert level in force at the time.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tsMs: Long,
    val profileId: Long,
    val profileName: String,
    val hex: String,
    val callsign: String?,
    /** ENTER, EXIT or POSITION. */
    val eventType: String,
    /** WATCH or WARNING (null for EXIT). */
    val tier: String?,
    val lat: Double?,
    val lon: Double?,
    val altMslFt: Double?,
    val aglFt: Double?,
    val distanceKm: Double?,
)

@Dao
interface EventDao {

    @Insert
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY tsMs")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY tsMs")
    suspend fun getAll(): List<EventEntity>

    @Query("DELETE FROM events WHERE tsMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM events")
    suspend fun clear()
}