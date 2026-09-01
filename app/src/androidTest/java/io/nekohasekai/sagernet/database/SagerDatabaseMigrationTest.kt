package io.nekohasekai.sagernet.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SagerDatabaseMigrationTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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

    @Test
    fun migrateVersion3ToCurrentPreservesData() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO `proxy_groups`
                    (`id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`,
                     `isSelector`, `frontProxy`, `landingProxy`)
                VALUES (8, 4, 0, 'version-3', 0, NULL, 0, 0, -1, -1)
                """.trimIndent()
            )
            close()
        }

        val database = openCurrentDatabase()
        try {
            database.openHelper.writableDatabase.query(
                "SELECT `name` FROM `proxy_groups` WHERE `id` = 8"
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("version-3", cursor.getString(0))
            }
            assertEquals(6, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrateVersion1ToCurrentPreservesData() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO `proxy_groups`
                    (`id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`)
                VALUES (9, 5, 0, 'version-1', 0, NULL, 0)
                """.trimIndent()
            )
            close()
        }

        val database = openCurrentDatabase()
        try {
            database.openHelper.writableDatabase.query(
                """
                SELECT `name`, `isSelector`, `frontProxy`, `landingProxy`
                FROM `proxy_groups` WHERE `id` = 9
                """.trimIndent()
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("version-1", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(-1L, cursor.getLong(2))
                assertEquals(-1L, cursor.getLong(3))
            }
            assertEquals(6, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
        }
    }

    private fun openCurrentDatabase() =
        Room.databaseBuilder(context, SagerDatabase::class.java, TEST_DB)
            .addMigrations(SagerDatabase.MIGRATION_1_2, SagerDatabase.MIGRATION_2_3)
            .build()

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
    }

    companion object {
        private const val TEST_DB = "sager-migration-test"
    }
}
