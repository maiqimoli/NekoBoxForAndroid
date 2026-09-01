package io.nekohasekai.sagernet.plugin

import android.util.AtomicFile
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object PluginTrustStore {

    private const val FILE_NAME = "trusted_plugins"
    private val processLock = ReentrantLock()

    fun read(): Set<String> = processLock.withLock {
        runCatching {
            withFileLock { readLocked() }
        }.onFailure { error ->
            Logs.w("Failed to read plugin trust store", error)
        }.getOrDefault(emptySet())
    }

    fun update(transform: (Set<String>) -> Set<String>) = processLock.withLock {
        withFileLock {
            val atomicFile = trustFile()
            val records = transform(readLocked())
            val output = atomicFile.startWrite()
            try {
                OutputStreamWriter(output, Charsets.UTF_8).buffered().apply {
                    records.sorted().forEach {
                        append(it)
                        newLine()
                    }
                    flush()
                }
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }
    }

    private fun readLocked(): Set<String> {
        return try {
            trustFile().openRead().bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter(String::isNotBlank).toSet()
            }
        } catch (_: java.io.FileNotFoundException) {
            emptySet()
        }
    }

    private inline fun <T> withFileLock(block: () -> T): T {
        val directory = app.noBackupFilesDir.apply { mkdirs() }
        return RandomAccessFile(File(directory, "$FILE_NAME.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { block() }
        }
    }

    private fun trustFile() = AtomicFile(File(app.noBackupFilesDir, FILE_NAME))
}
