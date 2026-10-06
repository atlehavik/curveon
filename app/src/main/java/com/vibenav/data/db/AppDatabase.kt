package com.vibenav.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vibenav.data.model.GpxTrack
import com.vibenav.data.model.Ride
import com.vibenav.data.model.RouteItem
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RouteItem::class, GpxTrack::class, Ride::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun gpxDao(): GpxDao
    abstract fun rideDao(): RideDao

    companion object {
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
                    "vibenav_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
