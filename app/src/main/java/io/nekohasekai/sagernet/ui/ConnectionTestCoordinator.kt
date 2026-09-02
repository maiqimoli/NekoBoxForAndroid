package io.nekohasekai.sagernet.ui

@JvmInline
internal value class ConnectionTestToken internal constructor(val value: Long)

internal enum class ConnectionTestStartDecision {
    REJECTED,
    ACCEPTED_HIDDEN,
    ACCEPTED_VISIBLE,
}

internal data class ConnectionTestCompletionDecision(
    val acceptedAtSeconds: Long?,
    val testingStateChanged: Boolean,
) {
    val accepted: Boolean
        get() = acceptedAtSeconds != null
}

/**
 * Owns the lifecycle of the single connection-test session in this process.
 *
 * The lock only protects in-memory state. Callers must perform UI, database and notification
 * work after the coordinator has returned.
 */
internal class ConnectionTestCoordinator(
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    private enum class Phase {
        RUNNING,
        FINISHING,
    }

    private data class Session(
        val token: ConnectionTestToken,
        var phase: Phase = Phase.RUNNING,
        var uiAttached: Boolean = true,
        val visibleTestingIds: MutableSet<Long> = linkedSetOf(),
    )

    private val lock = Any()
    private var nextToken = 1L
    private var active: Session? = null

    fun tryBegin(): ConnectionTestToken? = synchronized(lock) {
        if (active != null) return@synchronized null
        val token = ConnectionTestToken(nextToken++)
        active = Session(token)
        token
    }

    fun profileStarted(
        token: ConnectionTestToken,
        profileId: Long,
    ): ConnectionTestStartDecision {
        if (profileId <= 0L) return ConnectionTestStartDecision.REJECTED
        return synchronized(lock) {
            val session = active
            if (session?.token != token || session.phase != Phase.RUNNING) {
                return@synchronized ConnectionTestStartDecision.REJECTED
            }
            if (!session.uiAttached || !session.visibleTestingIds.add(profileId)) {
                ConnectionTestStartDecision.ACCEPTED_HIDDEN
            } else {
                ConnectionTestStartDecision.ACCEPTED_VISIBLE
            }
        }
    }

    fun profileCompleted(
        token: ConnectionTestToken,
        profileId: Long,
    ): ConnectionTestCompletionDecision {
        if (profileId <= 0L) return REJECTED_COMPLETION
        val completedAt = nowSeconds()
        return synchronized(lock) {
            val session = active
            if (session?.token != token || session.phase != Phase.RUNNING) {
                return@synchronized REJECTED_COMPLETION
            }
            ConnectionTestCompletionDecision(
                acceptedAtSeconds = completedAt,
                testingStateChanged = session.visibleTestingIds.remove(profileId),
            )
        }
    }

    fun detachUi(token: ConnectionTestToken): Set<Long> = synchronized(lock) {
        val session = active
        if (session?.token != token) return@synchronized emptySet()
        session.uiAttached = false
        session.takeVisibleTestingIds()
    }

    /**
     * Atomically closes result publication and acts as the one-shot finishing gate.
     */
    fun beginFinish(token: ConnectionTestToken): Set<Long>? = synchronized(lock) {
        val session = active
        if (session?.token != token || session.phase != Phase.RUNNING) {
            return@synchronized null
        }
        session.phase = Phase.FINISHING
        session.takeVisibleTestingIds()
    }

    /**
     * Releases ownership only when [token] still owns the active session.
     */
    fun endFinish(token: ConnectionTestToken): Boolean = synchronized(lock) {
        if (active?.token != token) return@synchronized false
        active = null
        true
    }

    fun isRunning(): Boolean = synchronized(lock) { active != null }

    fun isTesting(profileId: Long): Boolean = synchronized(lock) {
        active?.visibleTestingIds?.contains(profileId) == true
    }

    private fun Session.takeVisibleTestingIds(): Set<Long> {
        if (visibleTestingIds.isEmpty()) return emptySet()
        return visibleTestingIds.toSet().also { visibleTestingIds.clear() }
    }

    companion object {
        val shared = ConnectionTestCoordinator()

        private val REJECTED_COMPLETION = ConnectionTestCompletionDecision(
            acceptedAtSeconds = null,
            testingStateChanged = false,
        )
    }
}

internal data class CompletedConnectionTest(
    val profileId: Long,
    val status: Int,
    val ping: Int,
    val error: String?,
    val record: ProfileTestRecord,
)

/**
 * Writes best-effort connection-test results and returns records whose row update committed.
 */
internal fun persistConnectionTestResults(
    results: Collection<CompletedConnectionTest>,
    write: (CompletedConnectionTest) -> Int,
    onFailure: (CompletedConnectionTest, Exception) -> Unit = { _, _ -> },
): Map<Long, ProfileTestRecord> {
    val persisted = linkedMapOf<Long, ProfileTestRecord>()
    results.forEach { result ->
        try {
            if (write(result) == 1) persisted[result.profileId] = result.record
        } catch (error: Exception) {
            onFailure(result, error)
        }
    }
    return persisted
}
