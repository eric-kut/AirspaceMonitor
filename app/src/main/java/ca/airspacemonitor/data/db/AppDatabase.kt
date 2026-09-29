package ca.airspacemonitor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class, EventEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    abstract fun eventDao(): EventDao

    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningRadiusKm REAL")
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningCeilingValue REAL")
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningCeilingUnit TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningCeilingRef TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "tsMs INTEGER NOT NULL, " +
                        "profileId INTEGER NOT NULL, " +
                        "profileName TEXT NOT NULL, " +
                        "hex TEXT NOT NULL, " +
                        "callsign TEXT, " +
                        "eventType TEXT NOT NULL, " +
                        "tier TEXT, " +
                        "lat REAL, " +
                        "lon REAL, " +
                        "altMslFt REAL, " +
                        "aglFt REAL, " +
                        "distanceKm REAL)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningMode TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningCenterLat REAL")
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningCenterLon REAL")
                db.execSQL("ALTER TABLE profiles ADD COLUMN warningPolygonJson TEXT")
            }
        }

        /**
         * v4 inverts the layers: the mandatory warning zone moves into the base
         * geofence columns and the optional outer watch layer takes over the
         * (renamed) second-layer columns. Profiles that already had a warning
         * layer keep it as the mandatory zone and their old base zone becomes
         * the watch layer; profiles without one keep their zone as the warning
         * zone with watch disabled. Requires a table rebuild because the old
         * warning* columns are dropped.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profiles_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "geofenceMode TEXT NOT NULL, " +
                        "centerLat REAL, " +
                        "centerLon REAL, " +
                        "radiusKm REAL, " +
                        "polygonJson TEXT, " +
                        "ceilingValue REAL NOT NULL, " +
                        "ceilingUnit TEXT NOT NULL, " +
                        "ceilingRef TEXT NOT NULL, " +
                        "terrainElevM REAL, " +
                        "pollIntervalSec INTEGER NOT NULL, " +
                        "alertCooldownMin INTEGER NOT NULL, " +
                        "soundEnabled INTEGER NOT NULL, " +
                        "vibrationEnabled INTEGER NOT NULL, " +
                        "watchMode TEXT, " +
                        "watchRadiusKm REAL, " +
                        "watchCenterLat REAL, " +
                        "watchCenterLon REAL, " +
                        "watchPolygonJson TEXT, " +
                        "watchCeilingValue REAL, " +
                        "watchCeilingUnit TEXT, " +
                        "watchCeilingRef TEXT, " +
                        "watchOffsetHkm REAL, " +
                        "watchOffsetV REAL, " +
                        "watchOffsetVUnit TEXT)",
                )
                // With an old warning layer: warning -> base, old base -> watch.
                db.execSQL(
                    "INSERT INTO profiles_new (id, name, geofenceMode, centerLat, centerLon, radiusKm, " +
                        "polygonJson, ceilingValue, ceilingUnit, ceilingRef, terrainElevM, pollIntervalSec, " +
                        "alertCooldownMin, soundEnabled, vibrationEnabled, " +
                        "watchMode, watchRadiusKm, watchCenterLat, watchCenterLon, watchPolygonJson, " +
                        "watchCeilingValue, watchCeilingUnit, watchCeilingRef) " +
                        "SELECT id, name, COALESCE(warningMode, 'FIXED_CIRCLE'), " +
                        "COALESCE(warningCenterLat, centerLat), COALESCE(warningCenterLon, centerLon), " +
                        "warningRadiusKm, warningPolygonJson, warningCeilingValue, warningCeilingUnit, " +
                        "warningCeilingRef, terrainElevM, pollIntervalSec, alertCooldownMin, soundEnabled, " +
                        "vibrationEnabled, geofenceMode, radiusKm, centerLat, centerLon, polygonJson, " +
                        "ceilingValue, ceilingUnit, ceilingRef " +
                        "FROM profiles WHERE warningRadiusKm IS NOT NULL",
                )
                // Without an old warning layer: base stays (now meaning the warning zone), watch off.
                db.execSQL(
                    "INSERT INTO profiles_new (id, name, geofenceMode, centerLat, centerLon, radiusKm, " +
                        "polygonJson, ceilingValue, ceilingUnit, ceilingRef, terrainElevM, pollIntervalSec, " +
                        "alertCooldownMin, soundEnabled, vibrationEnabled) " +
                        "SELECT id, name, geofenceMode, centerLat, centerLon, radiusKm, polygonJson, " +
                        "ceilingValue, ceilingUnit, ceilingRef, terrainElevM, pollIntervalSec, " +
                        "alertCooldownMin, soundEnabled, vibrationEnabled " +
                        "FROM profiles WHERE warningRadiusKm IS NULL",
                )
                db.execSQL("DROP TABLE profiles")
                db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
            }
        }

        /**
         * v5 unifies units and adds the voice-alert toggles: the per-profile
         * ceiling-unit columns are dropped (everything is stored in feet; the
         * conversion happens on display), and metric-valued ceilings/offsets
         * are converted to feet during the table rebuild (fused into the
         * single INSERT so conversion-then-drop is inherently safe). The voice
         * booleans default off for migrated rows.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profiles_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "geofenceMode TEXT NOT NULL, " +
                        "centerLat REAL, " +
                        "centerLon REAL, " +
                        "radiusKm REAL, " +
                        "polygonJson TEXT, " +
                        "ceilingValue REAL NOT NULL, " +
                        "ceilingRef TEXT NOT NULL, " +
                        "terrainElevM REAL, " +
                        "pollIntervalSec INTEGER NOT NULL, " +
                        "alertCooldownMin INTEGER NOT NULL, " +
                        "soundEnabled INTEGER NOT NULL, " +
                        "vibrationEnabled INTEGER NOT NULL, " +
                        "warningVoiceEnabled INTEGER NOT NULL, " +
                        "watchVoiceEnabled INTEGER NOT NULL, " +
                        "watchMode TEXT, " +
                        "watchRadiusKm REAL, " +
                        "watchCenterLat REAL, " +
                        "watchCenterLon REAL, " +
                        "watchPolygonJson TEXT, " +
                        "watchCeilingValue REAL, " +
                        "watchCeilingRef TEXT, " +
                        "watchOffsetHkm REAL, " +
                        "watchOffsetV REAL)",
                )
                db.execSQL(
                    "INSERT INTO profiles_new (id, name, geofenceMode, centerLat, centerLon, radiusKm, " +
                        "polygonJson, ceilingValue, ceilingRef, terrainElevM, pollIntervalSec, " +
                        "alertCooldownMin, soundEnabled, vibrationEnabled, warningVoiceEnabled, " +
                        "watchVoiceEnabled, watchMode, watchRadiusKm, watchCenterLat, watchCenterLon, " +
                        "watchPolygonJson, watchCeilingValue, watchCeilingRef, watchOffsetHkm, watchOffsetV) " +
                        "SELECT id, name, geofenceMode, centerLat, centerLon, radiusKm, polygonJson, " +
                        "CASE WHEN ceilingUnit = 'M' THEN ROUND(ceilingValue / 0.3048, 2) ELSE ceilingValue END, " +
                        "ceilingRef, terrainElevM, pollIntervalSec, alertCooldownMin, soundEnabled, " +
                        "vibrationEnabled, 0, 0, " +
                        "watchMode, watchRadiusKm, watchCenterLat, watchCenterLon, watchPolygonJson, " +
                        "CASE WHEN watchCeilingUnit = 'M' THEN ROUND(watchCeilingValue / 0.3048, 2) " +
                        "ELSE watchCeilingValue END, " +
                        "watchCeilingRef, watchOffsetHkm, " +
                        "CASE WHEN watchOffsetVUnit = 'M' THEN ROUND(watchOffsetV / 0.3048, 2) " +
                        "ELSE watchOffsetV END " +
                        "FROM profiles",
                )
                db.execSQL("DROP TABLE profiles")
                db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
            }
        }

        /**
         * v6 widens the re-alert cooldown to 30-second resolution: the column
         * becomes REAL (fractional minutes). Values are carried over as-is —
         * an old "2" becomes "2.0 min", which is the same cooldown.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profiles_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "geofenceMode TEXT NOT NULL, " +
                        "centerLat REAL, " +
                        "centerLon REAL, " +
                        "radiusKm REAL, " +
                        "polygonJson TEXT, " +
                        "ceilingValue REAL NOT NULL, " +
                        "ceilingRef TEXT NOT NULL, " +
                        "terrainElevM REAL, " +
                        "pollIntervalSec INTEGER NOT NULL, " +
                        "alertCooldownMin REAL NOT NULL, " +
                        "soundEnabled INTEGER NOT NULL, " +
                        "vibrationEnabled INTEGER NOT NULL, " +
                        "warningVoiceEnabled INTEGER NOT NULL, " +
                        "watchVoiceEnabled INTEGER NOT NULL, " +
                        "watchMode TEXT, " +
                        "watchRadiusKm REAL, " +
                        "watchCenterLat REAL, " +
                        "watchCenterLon REAL, " +
                        "watchPolygonJson TEXT, " +
                        "watchCeilingValue REAL, " +
                        "watchCeilingRef TEXT, " +
                        "watchOffsetHkm REAL, " +
                        "watchOffsetV REAL)",
                )
                db.execSQL(
                    "INSERT INTO profiles_new (id, name, geofenceMode, centerLat, centerLon, radiusKm, " +
                        "polygonJson, ceilingValue, ceilingRef, terrainElevM, pollIntervalSec, " +
                        "alertCooldownMin, soundEnabled, vibrationEnabled, warningVoiceEnabled, " +
                        "watchVoiceEnabled, watchMode, watchRadiusKm, watchCenterLat, watchCenterLon, " +
                        "watchPolygonJson, watchCeilingValue, watchCeilingRef, watchOffsetHkm, watchOffsetV) " +
                        "SELECT id, name, geofenceMode, centerLat, centerLon, radiusKm, polygonJson, " +
                        "ceilingValue, ceilingRef, terrainElevM, pollIntervalSec, alertCooldownMin, " +
                        "soundEnabled, vibrationEnabled, warningVoiceEnabled, watchVoiceEnabled, " +
                        "watchMode, watchRadiusKm, watchCenterLat, watchCenterLon, watchPolygonJson, " +
                        "watchCeilingValue, watchCeilingRef, watchOffsetHkm, watchOffsetV " +
                        "FROM profiles",
                )
                db.execSQL("DROP TABLE profiles")
                db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "airspacemonitor.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}