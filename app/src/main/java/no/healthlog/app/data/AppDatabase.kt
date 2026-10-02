package no.healthlog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HealthDay::class], version = AppDatabase.DATABASE_VERSION, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun healthDayDao(): HealthDayDao

    companion object {
        const val DATABASE_NAME = "healthlog.db"

        /**
         * Øk denne når HealthDay endres, OG legg til en Migration fra forrige versjon i
         * ALL_MIGRATIONS under. DatabaseMigrationTest feiler hvis et steg mangler.
         */
        const val DATABASE_VERSION = 16

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN note TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN sleepScore INTEGER DEFAULT NULL")
            }
        }

        // SQLite kan ikke fjerne/omdøpe en kolonne direkte - må bygge en ny tabell med
        // riktig skjema, kopiere over dataen (gammel "note" havner i "eveningNote", siden
        // det tidligere dagboknotatet reflekterte dagen i etterkant), og bytte ut originalen.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE health_days_new (
                        date TEXT NOT NULL PRIMARY KEY,
                        weightKg REAL,
                        waistCm REAL,
                        hrvMs REAL,
                        sleepMinutes INTEGER,
                        sleepScore INTEGER,
                        restingHeartRate INTEGER,
                        bodyBatteryMax INTEGER,
                        bodyBatteryMin INTEGER,
                        stress INTEGER,
                        steps INTEGER,
                        trainingMinutes INTEGER,
                        morningNote TEXT,
                        eveningNote TEXT,
                        source TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO health_days_new (
                        date, weightKg, waistCm, hrvMs, sleepMinutes, sleepScore,
                        restingHeartRate, bodyBatteryMax, bodyBatteryMin, stress, steps,
                        trainingMinutes, morningNote, eveningNote, source, updatedAt
                    )
                    SELECT
                        date, weightKg, waistCm, hrvMs, sleepMinutes, sleepScore,
                        restingHeartRate, bodyBatteryMax, bodyBatteryMin, stress, steps,
                        trainingMinutes, NULL, note, source, updatedAt
                    FROM health_days
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE health_days")
                db.execSQL("ALTER TABLE health_days_new RENAME TO health_days")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN dayTag TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN trainingIntensity TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN trainingType TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN manualOverrides TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN nutritionLevel TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN trainingSessions TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN avgHeartRate INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN activeMinutes INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN deepSleepMinutes INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE health_days ADD COLUMN lightSleepMinutes INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE health_days ADD COLUMN remSleepMinutes INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE health_days ADD COLUMN sleepAwakeMinutes INTEGER DEFAULT NULL")
            }
        }

        // Egne vane-merker per dag (se habitTagSet i HealthDay.kt).
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN habitTags TEXT DEFAULT NULL")
            }
        }

        // Kroppsfett fra Health Connect (smartvekt).
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN bodyFatPercent REAL DEFAULT NULL")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_days ADD COLUMN sleepReflection TEXT DEFAULT NULL")
            }
        }

        /** Alle migreringer i rekkefølge - hver versjon må ha et steg til neste. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
            MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16
        )

        /**
         * Delt instans, siden appen nå har to steder som trenger databasen: MainActivity
         * (forgrunn) og HealthConnectSyncWorker (bakgrunn). Bygges kun én gang per prosess.
         */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // BEVISST ingen fallbackToDestructiveMigration: den slettet ALL data
                    // stille hvis en migrering manglet. Nå stopper appen i stedet med en
                    // tydelig feil (som du ser selv under testing), og brukernes data
                    // blir liggende urørt. DatabaseMigrationTest fanger manglende steg
                    // allerede før appen bygges.
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
