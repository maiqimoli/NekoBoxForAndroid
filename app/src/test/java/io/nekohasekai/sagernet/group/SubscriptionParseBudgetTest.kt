package io.nekohasekai.sagernet.group

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringReader

class SubscriptionParseBudgetTest {

    @Test
    fun `profile count accepts boundary and rejects excess`() {
        val budget = budget(maxProfiles = 2)

        budget.requireProfileCount(2)
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget.requireProfileCount(3)
        }
    }

    @Test
    fun `field limit counts UTF-8 bytes`() {
        budget(maxFieldBytes = 4).recordField("éé")

        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 3).recordField("éé")
        }
    }

    @Test
    fun `decoded byte and node limits accept boundary and reject excess`() {
        val bytes = budget(maxDecodedBytes = 4)
        bytes.recordDecodedText("éé")
        assertThrows(SubscriptionParseLimitException::class.java) {
            bytes.recordDecodedText("a")
        }

        val nodes = budget(maxNodes = 2)
        nodes.recordNode(2)
        assertThrows(SubscriptionParseLimitException::class.java) {
            nodes.recordNode()
        }
    }

    @Test
    fun `profile allocations share a stateful budget`() {
        val budget = budget(maxProfiles = 2)

        budget.recordProfile()
        budget.recordProfile()
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget.recordProfile()
        }
    }

    @Test
    fun `YAML event scan bounds flat collections before tree construction`() {
        val yaml = "- one\n- two\n- three"

        budget(maxNodes = 4).validateYamlEvents(yamlEvents(yaml))
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNodes = 3).validateYamlEvents(yamlEvents(yaml))
        }
    }

    @Test
    fun `YAML event scan checks scalar bytes depth and aliases`() {
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 3).validateYamlEvents(yamlEvents("- éé"))
        }
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNestingDepth = 1).validateYamlEvents(yamlEvents("- [value]"))
        }
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNodes = 5).validateYamlEvents(
                yamlEvents("shared: &shared [value]\ncopy: *shared"),
            )
        }
    }

    @Test
    fun `link token count is bounded independently of lines`() {
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxProfiles = 2).validateLinkText("a b c")
        }
    }

    @Test
    fun `line view enforces field size even when every token is short`() {
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 5).validateLinkText("aa aa aa")
        }
    }

    @Test
    fun `decoded map traversal validates keys and nested values`() {
        val tree = mapOf("outer" to listOf(mapOf("inner" to "value")))

        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 4).validateDecodedTree(tree)
        }
    }

    @Test
    fun `decoded JSON traversal observes arrays fields and nodes`() {
        val json = JSONObject().put("key", JSONArray().put("value"))

        budget(maxNodes = 4).validateDecodedTree(json)
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNodes = 3).validateDecodedTree(json)
        }
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 4).validateDecodedTree(json)
        }
    }

    @Test
    fun `JSON nesting is bounded before recursive parsing`() {
        budget(maxNestingDepth = 2).validateJsonNesting("{\"items\":[]}")
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNestingDepth = 2).validateJsonNesting("{\"items\":[[]]}")
        }
    }

    @Test
    fun `JSON nesting scan accepts the same leading BOM as JSONTokener`() {
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNestingDepth = 1).validateJsonNesting("\uFEFF[[0]]")
        }
    }

    @Test
    fun `JSON nesting ignores strings and comments`() {
        budget(maxNestingDepth = 1).validateJsonNesting(
            """{"value":"[[[{{{",/* [[[ */'other':'{{{'}""",
        )
    }

    @Test
    fun `JSON nesting probe ignores non-JSON text and trailing data`() {
        budget(maxNestingDepth = 1).validateJsonNesting("scheme://host/[[[")
        budget(maxNestingDepth = 1).validateJsonNesting("{}[[[")
    }

    @Test
    fun `JSON preflight counts decoded nodes without punctuation in strings or comments`() {
        val json = """{"a":[1,{"b":"value,:;=[]"}],/* ,:[] */"c":3}"""

        budget(maxNodes = 9).validateJsonNesting(json)
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNodes = 8).validateJsonNesting(json)
        }
    }

    @Test
    fun `INI preflight bounds peers nodes and UTF-8 fields`() {
        val ini = """
            [Interface]
            Address = 10.0.0.1/32
            [Peer]
            Endpoint = first.example:443
            [Peer]
            Endpoint = second.example:443
        """.trimIndent()

        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxProfiles = 1).validateIniText(ini)
        }
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxNodes = 8).validateIniText(ini)
        }
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 3).validateIniText("Key = éé")
        }
    }

    @Test
    fun `INI preflight bounds escaped logical lines`() {
        budget(maxFieldBytes = 8).validateIniText("Key=aa\\\naa")
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 7).validateIniText("Key=aa\\\naa")
        }
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 7).validateIniText("Key=a\\\n#12345\\\nz")
        }
    }

    @Test
    fun `INI preflight uses ini4j trim semantics`() {
        assertThrows(SubscriptionParseLimitException::class.java) {
            budget(maxFieldBytes = 5).validateIniText("\u2003Key\u2003")
        }
    }

    private fun yamlEvents(text: String) =
        Yaml(LoaderOptions()).parse(StringReader(text))

    private fun budget(
        maxProfiles: Int = Int.MAX_VALUE,
        maxFieldBytes: Int = Int.MAX_VALUE,
        maxDecodedBytes: Int = Int.MAX_VALUE,
        maxNodes: Int = Int.MAX_VALUE,
        maxNestingDepth: Int = Int.MAX_VALUE,
    ) = SubscriptionParseBudget(
        maxProfiles = maxProfiles,
        maxFieldBytes = maxFieldBytes,
        maxDecodedBytes = maxDecodedBytes,
        maxNodes = maxNodes,
        maxNestingDepth = maxNestingDepth,
    )
}
