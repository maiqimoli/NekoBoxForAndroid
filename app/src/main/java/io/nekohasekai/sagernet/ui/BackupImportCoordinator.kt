package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun <Snapshot> commitCompensatingImport(
    hasPrimaryChanges: Boolean,
    hasSecondaryChanges: Boolean,
    capturePrimary: suspend () -> Snapshot,
    applyPrimary: suspend () -> Unit,
    restorePrimary: suspend (Snapshot) -> Unit,
    applySecondary: suspend () -> Unit,
) {
    if (!hasPrimaryChanges) {
        if (hasSecondaryChanges) applySecondary()
        return
    }
    if (!hasSecondaryChanges) {
        applyPrimary()
        return
    }

    val snapshot = capturePrimary()
    applyPrimary()
    try {
        applySecondary()
    } catch (importError: Throwable) {
        try {
            withContext(NonCancellable) {
                restorePrimary(snapshot)
            }
        } catch (restoreError: Throwable) {
            importError.addSuppressed(restoreError)
        }
        throw importError
    }
}
