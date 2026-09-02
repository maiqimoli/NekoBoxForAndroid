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
    fun migrateEveryHistoricalVersionToCurrentPreservesAllEntities() {
        for (startVersion in 1 until CURRENT_VERSION) {
            context.deleteDatabase(TEST_DB)
            createRepresentativeDatabase(startVersion)

            val database = openCurrentDatabase()
            try {
                val sqlite = database.openHelper.writableDatabase
                assertEquals(
                    "version $startVersion should migrate to the current schema",
                    CURRENT_VERSION,
                    sqlite.version
                )
                sqlite.query(
                    """
                    SELECT `name`, `isSelector`, `frontProxy`, `landingProxy`
                    FROM `proxy_groups` WHERE `id` = $GROUP_ID
                    """.trimIndent()
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "group missing after $startVersion->$CURRENT_VERSION" }
                    assertEquals("version-$startVersion", cursor.getString(0))
                    if (startVersion == 1) {
                        assertEquals(0, cursor.getInt(1))
                        assertEquals(-1L, cursor.getLong(2))
                        assertEquals(-1L, cursor.getLong(3))
                    } else {
                        assertEquals(1, cursor.getInt(1))
                        assertEquals(21L, cursor.getLong(2))
                        assertEquals(22L, cursor.getLong(3))
                    }
                }
                sqlite.query(
                    "SELECT `groupId`, `uuid` FROM `proxy_entities` WHERE `id` = $PROXY_ID"
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "proxy missing after $startVersion->$CURRENT_VERSION" }
                    assertEquals(GROUP_ID, cursor.getLong(0))
                    assertEquals("proxy-$startVersion", cursor.getString(1))
                }
                sqlite.query(
                    "SELECT `name`, `config` FROM `rules` WHERE `id` = $RULE_ID"
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "rule missing after $startVersion->$CURRENT_VERSION" }
                    assertEquals("rule-$startVersion", cursor.getString(0))
                    assertEquals("", cursor.getString(1))
                }
            } finally {
                database.close()
            }
        }
    }

    private fun createRepresentativeDatabase(startVersion: Int) {
        helper.createDatabase(TEST_DB, startVersion).apply {
            val groupColumns = if (startVersion == 1) {
                "`id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`"
            } else {
                """
                `id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`,
                `isSelector`, `frontProxy`, `landingProxy`
                """.trimIndent()
            }
            val groupValues = if (startVersion == 1) {
                "$GROUP_ID, 3, 0, 'version-$startVersion', 0, NULL, 0"
            } else {
                "$GROUP_ID, 3, 0, 'version-$startVersion', 0, NULL, 0, 1, 21, 22"
            }
            execSQL("INSERT INTO `proxy_groups` ($groupColumns) VALUES ($groupValues)")
            execSQL(
                """
                INSERT INTO `proxy_entities`
                    (`id`, `groupId`, `type`, `userOrder`, `tx`, `rx`, `status`, `ping`, `uuid`)
                VALUES ($PROXY_ID, $GROUP_ID, 0, 4, 5, 6, 0, 7, 'proxy-$startVersion')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO `rules`
                    (`id`, `name`, `userOrder`, `enabled`, `domains`, `ip`, `port`, `sourcePort`,
                     `network`, `source`, `protocol`, `outbound`, `packages`)
                VALUES ($RULE_ID, 'rule-$startVersion', 8, 1, '', '', '', '', '', '', '', 0, '')
                """.trimIndent()
            )
            close()
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
        private const val CURRENT_VERSION = 6
        private const val GROUP_ID = 17L
        private const val PROXY_ID = 27L
        private const val RULE_ID = 37L
    }
}
