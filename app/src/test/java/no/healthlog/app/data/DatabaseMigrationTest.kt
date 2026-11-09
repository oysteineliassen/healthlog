package no.healthlog.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Appen har ikke lenger noen "slett alt"-nødløsning ved manglende migrering (se
 * AppDatabase). Denne testen sørger for at det aldri mangler et steg: glemmer du en
 * Migration når DATABASE_VERSION økes, feiler testen her i stedet for at appen
 * stopper hos brukerne.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseMigrationTest {

    /**
     * Kjører ALLE migreringene på en ekte database: lager tabellen slik den så ut i
     * versjon 1 (kolonnene migrering 3->4 kopierer, uten note/sleepScore som kom i 2 og
     * 3), legger inn en dag, og åpner den med Room. Room sammenligner da resultatet med
     * HealthDay og kaster hvis en kolonne mangler eller har feil type - det samme som
     * ville stoppet appen hos en bruker som oppdaterer fra en gammel versjon.
     */
    @Test
    fun `a version 1 database migrates to the current schema with data intact`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)

        val v1 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE health_days (
                                date TEXT NOT NULL PRIMARY KEY,
                                weightKg REAL, waistCm REAL, hrvMs REAL,
                                sleepMinutes INTEGER, restingHeartRate INTEGER,
                                bodyBatteryMax INTEGER, bodyBatteryMin INTEGER,
                                stress INTEGER, steps INTEGER, trainingMinutes INTEGER,
                                source TEXT NOT NULL, updatedAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v1.writableDatabase.execSQL(
            "INSERT INTO health_days (date, weightKg, hrvMs, steps, source, updatedAt) VALUES ('2025-01-01', 85.5, 33.0, 9000, 'manual', 1)"
        )
        v1.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val day = runBlocking { db.healthDayDao().get("2025-01-01") }
            assertNotNull(day)
            assertEquals(85.5, day!!.weightKg!!, 0.001)
            assertEquals(33.0, day.hrvMs!!, 0.001)
            assertEquals(9000, day.steps)
            assertEquals(null, day.sleepFactors)
            // Den nye raden kan også skrives og leses i dagens skjema.
            runBlocking { db.healthDayDao().upsert(day.copy(bodyFatPercent = 22.3, sleepReflection = "ok")) }
            assertEquals(22.3, runBlocking { db.healthDayDao().get("2025-01-01") }!!.bodyFatPercent!!, 0.001)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }


    @Test
    fun `every version step up to the current version has a migration`() {
        val steps = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        for (version in 1 until AppDatabase.DATABASE_VERSION) {
            assertTrue("Mangler migrering $version -> ${version + 1}", (version to version + 1) in steps)
        }
    }

    @Test
    fun `migrations only take single steps and never go past the current version`() {
        AppDatabase.ALL_MIGRATIONS.forEach { migration ->
            assertEquals("Migrering ${migration.startVersion} -> ${migration.endVersion}", migration.startVersion + 1, migration.endVersion)
            assertTrue(migration.endVersion <= AppDatabase.DATABASE_VERSION)
        }
    }
}
