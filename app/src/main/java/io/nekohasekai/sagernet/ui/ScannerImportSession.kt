package io.nekohasekai.sagernet.ui

import java.util.concurrent.atomic.AtomicBoolean

internal class ScannerImportSession {
    private val started = AtomicBoolean(false)

    fun tryStart(): Boolean = started.compareAndSet(false, true)

    suspend fun <T> runBatch(items: Iterable<T>, importItem: suspend (T) -> Unit) {
        for (item in items) importItem(item)
    }
}
