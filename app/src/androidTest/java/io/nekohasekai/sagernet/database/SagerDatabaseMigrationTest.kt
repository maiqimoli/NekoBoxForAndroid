package io.nekohasekai.sagernet.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SagerDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java
    )

    @Test
    fun migrateVersion1To3PreservesGroupsAndAddsDefaults() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO `proxy_groups`
                    (`id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`)
                VALUES (7, 3, 0, 'legacy', 0, NULL, 0)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            SagerDatabase.MIGRATION_1_2,
            SagerDatabase.MIGRATION_2_3
        ).use { database ->
            database.query(
                """
                SELECT `name`, `isSelector`, `frontProxy`, `landingProxy`
                FROM `proxy_groups` WHERE `id` = 7
                """.trimIndent()
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("legacy", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(-1L, cursor.getLong(2))
                assertEquals(-1L, cursor.getLong(3))
            }
        }
    }

    companion object {
        private const val TEST_DB = "sager-migration-test"
    }
}
