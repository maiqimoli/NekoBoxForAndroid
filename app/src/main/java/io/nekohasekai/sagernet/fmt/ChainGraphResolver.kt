package io.nekohasekai.sagernet.fmt

internal const val MAX_PROXY_CHAIN_DEPTH = 32

/**
 * Expands chain nodes from an in-memory snapshot.
 *
 * A null [childrenOf] result identifies a leaf. Missing child ids retain the historical
 * ConfigBuilder behaviour and are skipped, while cycles and excessive nesting are reported
 * before they can overflow the stack.
 */
internal class ChainGraphResolver<T>(
    private val nodesById: Map<Long, T>,
    private val idOf: (T) -> Long,
    private val childrenOf: (T) -> List<Long>?,
    private val describe: (T) -> String = { idOf(it).toString() },
    private val maxDepth: Int = MAX_PROXY_CHAIN_DEPTH,
) {

    init {
        require(maxDepth > 0) { "Maximum proxy chain depth must be positive" }
    }

    fun resolve(root: T): List<T> = resolve(root, ArrayList())

    private fun resolve(node: T, path: MutableList<T>): List<T> {
        val children = childrenOf(node) ?: return listOf(node)
        val nodeId = idOf(node)
        val cycleStart = path.indexOfFirst { idOf(it) == nodeId }
        if (cycleStart >= 0) {
            val cycle = path.subList(cycleStart, path.size) + node
            throw IllegalStateException(
                "Proxy chain cycle detected: ${cycle.joinToString(" -> ", transform = describe)}"
            )
        }
        if (path.size >= maxDepth) {
            val exceededPath = path + node
            throw IllegalStateException(
                "Proxy chain exceeds maximum depth $maxDepth: " +
                    exceededPath.joinToString(" -> ", transform = describe)
            )
        }

        path.add(node)
        return try {
            val resolved = ArrayList<T>()
            for (childId in children) {
                val child = nodesById[childId] ?: continue
                resolved.addAll(resolve(child, path))
            }
            resolved.reverse()
            resolved
        } finally {
            path.removeAt(path.lastIndex)
        }
    }
}
