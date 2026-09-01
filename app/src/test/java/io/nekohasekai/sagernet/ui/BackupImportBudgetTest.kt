package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupImportBudgetTest {

    @Test
    fun acceptsSectionEntryLimits() {
        val budget = BackupImportBudget()

        budget.requireEntryCount("profiles", BackupImportLimits.MAX_PROFILE_ENTRIES)
        budget.requireEntryCount("groups", BackupImportLimits.MAX_GROUP_ENTRIES)
        budget.requireEntryCount("rules", BackupImportLimits.MAX_RULE_ENTRIES)
        budget.requireEntryCount("settings", BackupImportLimits.MAX_SETTING_ENTRIES)
    }

    @Test
    fun rejectsSectionAboveEntryLimit() {
        val budget = BackupImportBudget()

        assertThrows(IllegalArgumentException::class.java) {
            budget.requireEntryCount("groups", BackupImportLimits.MAX_GROUP_ENTRIES + 1)
        }
    }

    @Test
    fun rejectsEncodedItemBeforeDecoding() {
        val budget = BackupImportBudget(maxEncodedItemChars = 8)

        budget.requireEncodedItem("profiles", 0, 8)
        assertThrows(IllegalArgumentException::class.java) {
            budget.requireEncodedItem("profiles", 1, 9)
        }
    }

    @Test
    fun enforcesDecodedItemAndTotalLimits() {
        val budget = BackupImportBudget(
            maxDecodedItemBytes = 4,
            maxDecodedTotalBytes = 6,
        )

        budget.recordDecodedItem("profiles", 0, 4)
        budget.recordDecodedItem("groups", 0, 2)
        assertThrows(IllegalArgumentException::class.java) {
            budget.recordDecodedItem("rules", 0, 1)
        }

        val itemBudget = BackupImportBudget(maxDecodedItemBytes = 4)
        assertThrows(IllegalArgumentException::class.java) {
            itemBudget.recordDecodedItem("profiles", 0, 5)
        }
    }

    @Test
    fun exportAndImportUseTheSameEntryAndByteBudget() {
        val exportBudget = BackupImportBudget(
            maxDecodedItemBytes = 4,
            maxDecodedTotalBytes = 6,
            maxEncodedItemChars = 8,
        )
        val importBudget = BackupImportBudget(
            maxDecodedItemBytes = 4,
            maxDecodedTotalBytes = 6,
            maxEncodedItemChars = 8,
        )

        listOf(exportBudget, importBudget).forEach { budget ->
            budget.requireEntryCount("profiles", 1)
            budget.requireEncodedItem("profiles", 0, 8)
            budget.recordDecodedItem("profiles", 0, 4)
            budget.requireEntryCount("settings", 1)
            budget.requireEncodedItem("settings", 0, 4)
            budget.recordDecodedItem("settings", 0, 2)
        }
    }
}
