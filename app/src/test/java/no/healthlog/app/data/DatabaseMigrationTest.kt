package no.healthlog.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Appen har ikke lenger noen "slett alt"-nødløsning ved manglende migrering (se
 * AppDatabase). Denne testen sørger for at det aldri mangler et steg: glemmer du en
 * Migration når DATABASE_VERSION økes, feiler testen her i stedet for at appen
 * stopper hos brukerne.
 */
class DatabaseMigrationTest {

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
