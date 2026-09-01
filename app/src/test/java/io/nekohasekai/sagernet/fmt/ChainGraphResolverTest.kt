package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainGraphResolverTest {

    private data class Node(
        val id: Long,
        val children: List<Long>? = null,
    )

    private fun resolver(nodes: List<Node>, maxDepth: Int = MAX_PROXY_CHAIN_DEPTH) =
        ChainGraphResolver(
            nodesById = nodes.associateBy(Node::id),
            idOf = Node::id,
            childrenOf = Node::children,
            describe = { "node-${it.id}" },
            maxDepth = maxDepth,
        )

    @Test
    fun resolvePreservesNestedChainOrderAndSkipsMissingProfiles() {
        val root = Node(1, listOf(2, 3, 99))
        val nodes = listOf(
            root,
            Node(2, listOf(4, 5)),
            Node(3),
            Node(4),
            Node(5),
        )

        val resolved = resolver(nodes).resolve(root)

        assertEquals(listOf(3L, 4L, 5L), resolved.map(Node::id))
    }

    @Test
    fun resolveReportsTheCyclePath() {
        val root = Node(1, listOf(2))
        val nodes = listOf(
            root,
            Node(2, listOf(3)),
            Node(3, listOf(2)),
        )

        val error = runCatching { resolver(nodes).resolve(root) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("node-2 -> node-3 -> node-2"))
    }

    @Test
    fun resolveReportsExcessiveNestingBeforeStackOverflow() {
        val nodes = (1L..5L).map { id ->
            Node(id, if (id < 5L) listOf(id + 1L) else emptyList())
        }

        val error = runCatching { resolver(nodes, maxDepth = 3).resolve(nodes.first()) }
            .exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("maximum depth 3"))
        assertTrue(error?.message.orEmpty().contains("node-1 -> node-2 -> node-3 -> node-4"))
    }
}
