package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawUpdaterAlgorithmsTest {

    private data class Item(var name: String, val key: String)

    @Test
    fun `duplicate names receive stable suffixes in one pass`() {
        val items = listOf(
            Item("alpha", "1"),
            Item("alpha (1)", "2"),
            Item("alpha", "3"),
            Item("alpha", "4"),
            Item("alpha (1)", "5"),
        )

        ensureUniqueNames(items, Item::name) { item, name -> item.name = name }

        assertEquals(
            listOf("alpha", "alpha (1)", "alpha (2)", "alpha (3)", "alpha (1) (1)"),
            items.map(Item::name),
        )
        assertEquals(items.size, items.map(Item::name).toSet().size)
    }

    @Test
    fun `large equal-name input remains unique and ordered`() {
        val items = List(10_000) { index -> Item("node", index.toString()) }

        ensureUniqueNames(items, Item::name) { item, name -> item.name = name }

        assertEquals("node", items.first().name)
        assertEquals("node (9999)", items.last().name)
        assertEquals(items.size, items.map(Item::name).toSet().size)
    }

    @Test
    fun `deduplication keeps first occurrence and preserves duplicate labels`() {
        val items = listOf(
            Item("first", "same"),
            Item("other", "unique"),
            Item("second", "same"),
            Item("third", "same"),
        )

        val result = deduplicateKeepingFirst(items, Item::key, Item::name)

        assertEquals(listOf("first", "other"), result.unique.map(Item::name))
        assertEquals(
            listOf("first (0)", "second (0)", "third (0)"),
            result.duplicateNames,
        )
    }

    @Test
    fun `identity reuse requires an unambiguous key on both sides`() {
        val existing = listOf(
            Item("old-a", "a"),
            Item("old-b-1", "b"),
            Item("old-b-2", "b"),
        )
        val incoming = listOf(
            Item("renamed-a", "a"),
            Item("new-b", "b"),
        )

        val matches = matchUniqueByKey(existing, incoming, Item::key, Item::key)

        assertEquals(1, matches.size)
        assertEquals("old-a", matches.single().existing.name)
        assertEquals("renamed-a", matches.single().incoming.name)
        assertTrue(matches.none { it.incoming.key == "b" })
    }
}
