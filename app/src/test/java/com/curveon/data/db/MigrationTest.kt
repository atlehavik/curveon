package com.curveon.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A rider who updates keeps every saved route, GPX track and ride: from each database version a release has had
 * (4 in 1.0, 5 only between builds, 6 in 1.1, 7 since 1.2.2) the migrations give exactly the schema of today. The old
 * database is made from its schema in app/schemas (taken from the source of those releases), then opened by Room as the
 * app does, which checks every table after migrating and refuses a schema that differs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun create(name: String, version: Int) {
        val schema = JSONObject(File("schemas/com.curveon.data.db.AppDatabase/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) execSQL(setup.getString(i))
            setVersion(version)

            val planned = if (version >= 6) ", inWhereTo, inPlanned" to ", 1, 1" else "" to ""
            execSQL(
                "INSERT INTO saved_routes (title, subtitle, totalDistanceKm, estimatedMinutes, roadType, groundType, curvinessScore, " +
                    "elevationGainMeters, isRoundtrip, polylineJson, waypointsJson, timestamp${planned.first}) " +
                    "VALUES ('Lofoten', 'via Å', 412.5, 380, 'mixed', 'paved', 87, 2100, 0, '[]', '[]', 1700000000000${planned.second})"
            )
            execSQL(
                "INSERT INTO gpx_tracks (name, filename, totalDistanceKm, elevationGainMeters, maxElevationMeters, pointCount, trackPointsJson, " +
                    "waypointsJson, isFavorite, category, importedAtMs, showOnMap, color, showPois, reversed) " +
                    "VALUES ('Trollstigen', 'troll.gpx', 55.0, 900, 850, 2, '[]', '[]', 1, 'road', 1700000000000, 1, -65536, 1, 0)"
            )
            val lean = if (version < 7) ", maxLeanLeftDeg, maxLeanRightDeg" to ", 41.0, 38.0" else "" to ""
            execSQL(
                "INSERT INTO rides (title, startMs, endMs, distanceMeters, movingMillis, maxSpeedKmh, ascentMeters${lean.first}, track) " +
                    "VALUES ('Morning ride', 1700000000000, 1700003600000, 81234.0, 3400000, 112.0, 640.0${lean.second}, '[]')"
            )
            close()
        }
    }

    private fun migratesKeepingEverything(from: Int) {
        val name = "migration-test-$from"
        create(name, from)
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
        room.openHelper.readableDatabase.apply {
            assertEquals(7, version)
            fun count(table: String) = query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, count("saved_routes"))
            assertEquals(1, count("gpx_tracks"))
            assertEquals(1, count("rides"))
            query("SELECT title, distanceMeters FROM rides").use {
                it.moveToFirst()
                assertEquals("Morning ride", it.getString(0))
                assertEquals(81234.0, it.getDouble(1), 0.0)
            }
            query("SELECT title, inWhereTo FROM saved_routes").use {
                it.moveToFirst()
                assertEquals("Lofoten", it.getString(0))
                assertEquals(1, it.getInt(1))
            }
        }
        room.close()
    }

    @Test fun from4() = migratesKeepingEverything(4)

    @Test fun from5() = migratesKeepingEverything(5)

    @Test fun from6() = migratesKeepingEverything(6)

    /** The schema in the repo is the one the app builds: a change to an entity without a new version and migration fails here. */
    @Test fun from7() = migratesKeepingEverything(7)
}
