package com.curveon.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.curveon.data.model.GpxGroup
import com.curveon.data.model.GpxTrack
import com.curveon.data.model.Ride
import com.curveon.data.model.RouteGroup
import com.curveon.data.model.RouteItem
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RouteItem::class, RouteGroup::class, GpxTrack::class, GpxGroup::class, Ride::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun gpxDao(): GpxDao
    abstract fun rideDao(): RideDao

    companion object {
        /** v6: a saved route is in Where to? and/or among the planned trips (old ones stay in Where to? only). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `saved_routes` ADD COLUMN `inWhereTo` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `saved_routes` ADD COLUMN `inPlanned` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5: planned trips can be kept in named groups. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `route_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
                db.execSQL("ALTER TABLE `saved_routes` ADD COLUMN `groupId` INTEGER")
            }
        }

        /** v4: GPX groups and inverted tracks. Files imported with several tracks become a group. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gpx_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`visibility` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `sameColor` INTEGER NOT NULL, `color` INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE `gpx_tracks` ADD COLUMN `groupId` INTEGER")
                db.execSQL("ALTER TABLE `gpx_tracks` ADD COLUMN `reversed` INTEGER NOT NULL DEFAULT 0")
                val files = mutableListOf<String>()
                db.query("SELECT filename FROM gpx_tracks WHERE filename != '' GROUP BY filename HAVING COUNT(*) > 1").use { c ->
                    while (c.moveToNext()) files += c.getString(0)
                }
                for (f in files) {
                    val names = mutableListOf<String>()
                    db.query("SELECT name FROM gpx_tracks WHERE filename = ?", arrayOf(f)).use { c -> while (c.moveToNext()) names += c.getString(0) }
                    db.execSQL("INSERT INTO gpx_groups (name, visibility, direction, sameColor, color) VALUES (?, 0, 0, 0, 0)",
                        arrayOf(com.curveon.data.gpx.GpxGroups.defaultName(f, names)))
                    db.execSQL("UPDATE gpx_tracks SET groupId = last_insert_rowid() WHERE filename = ?", arrayOf(f))
                }
            }
        }

        /** v2 adds the ride log; keeps saved routes and GPX tracks. */
        /** v3: GPX overlays (visibility, colour, POIs). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `gpx_tracks` ADD COLUMN `showOnMap` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `gpx_tracks` ADD COLUMN `color` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `gpx_tracks` ADD COLUMN `showPois` INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rides` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, `movingMillis` INTEGER NOT NULL, " +
                        "`maxSpeedKmh` REAL NOT NULL, `ascentMeters` REAL NOT NULL, `maxLeanLeftDeg` REAL NOT NULL, " +
                        "`maxLeanRightDeg` REAL NOT NULL, `track` TEXT NOT NULL)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "curveon_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
