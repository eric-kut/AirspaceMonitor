package ca.trafficwatcher.data

import ca.trafficwatcher.data.db.ProfileDao
import ca.trafficwatcher.data.db.ProfileEntity
import ca.trafficwatcher.data.db.toDomain
import ca.trafficwatcher.data.db.toEntity
import ca.trafficwatcher.domain.CeilingRef
import ca.trafficwatcher.domain.CeilingUnit
import ca.trafficwatcher.domain.GeoPoint
import ca.trafficwatcher.domain.GeofenceMode
import ca.trafficwatcher.domain.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepository(private val dao: ProfileDao) {

    val profiles: Flow<List<Profile>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun get(id: Long): Profile? = dao.getById(id)?.toDomain()

    /** Insert or update. Returns the row id (0 for updates). */
    suspend fun save(profile: Profile): Long {
        return if (profile.id == 0L) {
            dao.insert(profile.toEntity())
        } else {
            dao.update(profile.toEntity())
            profile.id
        }
    }

    suspend fun duplicate(id: Long): Long {
        val src = dao.getById(id) ?: return -1
        val copy = src.copy(id = 0L, name = "${src.name} (copy)")
        return dao.insert(copy)
    }

    suspend fun delete(id: Long) {
        dao.getById(id)?.let { dao.delete(it) }
    }

    /** Seed one example profile the first time the app runs. */
    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        val example = ProfileEntity(
            id = 0L,
            name = "Example: 5 km circle, 400 ft AGL",
            geofenceMode = GeofenceMode.FIXED_CIRCLE.name,
            centerLat = 45.4215,
            centerLon = -75.6972,
            radiusKm = 5.0,
            polygonJson = null,
            ceilingValue = 400.0,
            ceilingUnit = CeilingUnit.FT.name,
            ceilingRef = CeilingRef.AGL.name,
            terrainElevM = 70.0,
            pollIntervalSec = 12,
            alertCooldownMin = 2,
            soundEnabled = true,
            vibrationEnabled = true,
        )
        dao.insert(example)
    }
}