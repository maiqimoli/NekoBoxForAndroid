package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.DataStore
import java.util.concurrent.ConcurrentHashMap

enum class ProfileTestMethod(val key: String) {
    UNKNOWN("unknown"),
    TCP("tcp"),
    URL("url");

    companion object {
        fun fromKey(key: String?): ProfileTestMethod =
            entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

data class ProfileTestRecord(
    val timestampSeconds: Long,
    val method: ProfileTestMethod = ProfileTestMethod.UNKNOWN,
)

object ProfileUiState {

    private const val MAX_RECENT_PROFILES = 20
    private const val MAX_TEST_TIMES = 300
    const val TEST_RESULT_EXPIRES_AFTER_SECONDS = 30 * 60L
    private val pendingTestRecords = ConcurrentHashMap<Long, ProfileTestRecord>()
    @Volatile
    private var cachedTestRecordsRaw = ""
    @Volatile
    private var cachedTestRecords: Map<Long, ProfileTestRecord> = emptyMap()

    internal fun decodeIds(value: String): List<Long> = value
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .filter { it > 0L }
        .distinct()

    private fun encodeIds(ids: Collection<Long>): String = ids.joinToString(",")

    internal fun decodeTestRecords(value: String): Map<Long, ProfileTestRecord> = value
        .split(',')
        .mapNotNull { entry ->
            val parts = entry.split(':', limit = 3)
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val timestamp = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (id > 0L && timestamp > 0L) {
                id to ProfileTestRecord(timestamp, ProfileTestMethod.fromKey(parts.getOrNull(2)))
            } else {
                null
            }
        }
        .toMap()

    internal fun decodeTestTimes(value: String): Map<Long, Long> =
        decodeTestRecords(value).mapValues { it.value.timestampSeconds }

    private fun encodeTestRecords(values: Map<Long, ProfileTestRecord>): String = values.entries
        .sortedByDescending { it.value.timestampSeconds }
        .take(MAX_TEST_TIMES)
        .joinToString(",") { "${it.key}:${it.value.timestampSeconds}:${it.value.method.key}" }

    private fun persistedTestRecords(): Map<Long, ProfileTestRecord> {
        val raw = DataStore.profileTestTimes
        if (raw == cachedTestRecordsRaw) return cachedTestRecords
        return synchronized(this) {
            if (raw != cachedTestRecordsRaw) {
                cachedTestRecords = decodeTestRecords(raw)
                cachedTestRecordsRaw = raw
            }
            cachedTestRecords
        }
    }

    private fun saveTestRecords(values: Map<Long, ProfileTestRecord>) {
        val encoded = encodeTestRecords(values)
        DataStore.profileTestTimes = encoded
        cachedTestRecords = decodeTestRecords(encoded)
        cachedTestRecordsRaw = encoded
    }

    fun favoriteIds(): Set<Long> = decodeIds(DataStore.favoriteProfiles).toSet()

    fun recentIds(): List<Long> = decodeIds(DataStore.recentProfiles)

    internal fun normalizeRecentIds(profileIds: Collection<Long>): List<Long> = profileIds
        .asSequence()
        .filter { it > 0L }
        .distinct()
        .take(MAX_RECENT_PROFILES)
        .toList()

    fun lastTestAt(profileId: Long): Long? = testRecord(profileId)?.timestampSeconds

    fun lastTestMethod(profileId: Long): ProfileTestMethod =
        testRecord(profileId)?.method ?: ProfileTestMethod.UNKNOWN

    private fun testRecord(profileId: Long): ProfileTestRecord? =
        pendingTestRecords[profileId] ?: persistedTestRecords()[profileId]

    fun isTestFresh(
        profileId: Long,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Boolean {
        val testedAt = lastTestAt(profileId) ?: return false
        return nowSeconds - testedAt in 0..TEST_RESULT_EXPIRES_AFTER_SECONDS
    }

    fun markTestedPending(
        profileId: Long,
        method: ProfileTestMethod,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L,
    ) {
        if (profileId <= 0L || timestampSeconds <= 0L) return
        pendingTestRecords[profileId] = ProfileTestRecord(timestampSeconds, method)
    }

    /**
     * Records timestamps only for connection-test results that have already been written to the
     * profile database.
     */
    @Synchronized
    fun recordPersistedTests(records: Map<Long, ProfileTestRecord>) {
        val committed = records.filter { (profileId, record) ->
            profileId > 0L && record.timestampSeconds > 0L
        }
        if (committed.isEmpty()) return
        val values = LinkedHashMap(persistedTestRecords())
        values.putAll(committed)
        saveTestRecords(values)
        committed.forEach { (profileId, record) ->
            pendingTestRecords.remove(profileId, record)
        }
    }

    @Synchronized
    fun flushTested(profileIds: Collection<Long>) {
        if (profileIds.isEmpty()) return
        val values = LinkedHashMap(persistedTestRecords())
        val flushed = mutableMapOf<Long, ProfileTestRecord>()
        profileIds.asSequence().filter { it > 0L }.distinct().forEach { id ->
            pendingTestRecords[id]?.let { record ->
                values[id] = record
                flushed[id] = record
            }
        }
        if (flushed.isEmpty()) return
        saveTestRecords(values)
        flushed.forEach { (id, record) -> pendingTestRecords.remove(id, record) }
    }

    @Synchronized
    fun clearTested(profileIds: Collection<Long>) {
        val ids = profileIds.asSequence().filter { it > 0L }.toSet()
        if (ids.isEmpty()) return
        ids.forEach(pendingTestRecords::remove)
        saveTestRecords(persistedTestRecords().filterKeys { it !in ids })
    }

    @Synchronized
    fun toggleFavorite(profileId: Long): Boolean {
        val favorites = LinkedHashSet(decodeIds(DataStore.favoriteProfiles))
        val favorite = if (profileId in favorites) {
            favorites.remove(profileId)
            false
        } else {
            favorites.add(profileId)
            true
        }
        DataStore.favoriteProfiles = encodeIds(favorites)
        return favorite
    }

    @Synchronized
    fun setFavorites(profileIds: Collection<Long>, favorite: Boolean) {
        val favorites = LinkedHashSet(decodeIds(DataStore.favoriteProfiles))
        profileIds.filter { it > 0L }.forEach { id ->
            if (favorite) favorites.add(id) else favorites.remove(id)
        }
        DataStore.favoriteProfiles = encodeIds(favorites)
    }

    @Synchronized
    fun markRecent(profileId: Long) {
        if (profileId <= 0L) return
        val recent = buildList {
            add(profileId)
            addAll(decodeIds(DataStore.recentProfiles).filterNot { it == profileId })
        }.take(MAX_RECENT_PROFILES)
        DataStore.recentProfiles = encodeIds(recent)
    }

    @Synchronized
    fun removeRecent(profileId: Long): Boolean {
        if (profileId <= 0L) return false
        val recent = decodeIds(DataStore.recentProfiles)
        val updated = recent.filterNot { it == profileId }
        if (updated.size == recent.size) return false
        DataStore.recentProfiles = encodeIds(updated)
        return true
    }

    @Synchronized
    fun clearRecent(): List<Long> {
        val recent = decodeIds(DataStore.recentProfiles)
        DataStore.recentProfiles = ""
        return recent
    }

    @Synchronized
    fun restoreRecent(profileIds: Collection<Long>) {
        DataStore.recentProfiles = encodeIds(normalizeRecentIds(profileIds))
    }

    @Synchronized
    fun removeProfile(profileId: Long) {
        if (profileId <= 0L) return
        pendingTestRecords.remove(profileId)
        DataStore.favoriteProfiles = encodeIds(
            decodeIds(DataStore.favoriteProfiles).filterNot { it == profileId }
        )
        DataStore.recentProfiles = encodeIds(
            decodeIds(DataStore.recentProfiles).filterNot { it == profileId }
        )
        saveTestRecords(persistedTestRecords().filterKeys { it != profileId })
    }
}
