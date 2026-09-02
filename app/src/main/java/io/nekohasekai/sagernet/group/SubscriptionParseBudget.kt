package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_DECODED_BYTES
import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_DECODED_NODES
import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_FIELD_BYTES
import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_NESTING_DEPTH
import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_PROFILES
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.MappingEndEvent
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceEndEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import java.util.Collections
import java.util.IdentityHashMap

internal class SubscriptionParseLimitException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Bounds allocations created after a subscription has passed the raw input-size check.
 *
 * A fresh instance is used for every decoding attempt so failed format probes do not consume the
 * budget of the format that eventually succeeds.
 */
internal class SubscriptionParseBudget(
    private val maxProfiles: Int = MAX_SUBSCRIPTION_PROFILES,
    private val maxFieldBytes: Int = MAX_SUBSCRIPTION_FIELD_BYTES,
    private val maxDecodedBytes: Int = MAX_SUBSCRIPTION_DECODED_BYTES,
    private val maxNodes: Int = MAX_SUBSCRIPTION_DECODED_NODES,
    private val maxNestingDepth: Int = MAX_SUBSCRIPTION_NESTING_DEPTH,
) {

    private var decodedBytes = 0L
    private var nodes = 0L
    private var profiles = 0

    init {
        require(maxProfiles >= 0)
        require(maxFieldBytes >= 0)
        require(maxDecodedBytes >= 0)
        require(maxNodes >= 0)
        require(maxNestingDepth >= 0)
    }

    fun requireProfileCount(count: Int) {
        if (count > maxProfiles) {
            throw SubscriptionParseLimitException(
                "Subscription contains more than $maxProfiles profiles",
            )
        }
    }

    fun requireProfileCapacity() {
        requireProfileCount(profiles + 1)
    }

    fun recordProfile() {
        requireProfileCapacity()
        profiles++
    }

    fun recordNode(count: Int = 1) {
        require(count >= 0)
        nodes += count.toLong()
        if (nodes > maxNodes) {
            throw SubscriptionParseLimitException(
                "Subscription decoded structure exceeds $maxNodes nodes",
            )
        }
    }

    fun recordField(value: String, includeInDecodedBytes: Boolean = true) {
        val fieldBytes = value.toByteArray(Charsets.UTF_8).size
        if (fieldBytes > maxFieldBytes) {
            throw SubscriptionParseLimitException(
                "Subscription field exceeds $maxFieldBytes bytes",
            )
        }
        if (includeInDecodedBytes) recordDecodedBytes(fieldBytes)
    }

    fun recordDecodedText(value: String) {
        recordDecodedBytes(value.toByteArray(Charsets.UTF_8).size)
    }

    /**
     * Walks SnakeYAML's lazy event stream before the composer constructs Maps and Lists. A fresh
     * budget is used later for the decoded tree because the two passes describe the same data.
     */
    fun validateYamlEvents(events: Iterable<Event>) {
        var depth = 0

        for (event in events) {
            when (event) {
                is MappingStartEvent, is SequenceStartEvent -> {
                    recordNode()
                    depth++
                    if (depth > maxNestingDepth) {
                        throw SubscriptionParseLimitException(
                            "Subscription YAML exceeds nesting depth $maxNestingDepth",
                        )
                    }
                }

                is MappingEndEvent, is SequenceEndEvent -> {
                    if (depth > 0) depth--
                }

                is ScalarEvent -> {
                    recordNode()
                    recordField(event.value)
                }

                is AliasEvent -> recordNode()
            }
        }
    }

    /** Validates decoded YAML/JSON containers without recursively using the call stack. */
    fun validateDecodedTree(root: Any?) {
        if (root == null) return

        val pending = ArrayDeque<Any>()
        val visitedContainers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        pending.addLast(root)

        while (pending.isNotEmpty()) {
            val value = pending.removeLast()
            recordNode()
            when (value) {
                is String -> recordField(value)
                is Map<*, *> -> {
                    if (!visitedContainers.add(value)) continue
                    value.forEach { (key, child) ->
                        if (key != null) pending.addLast(key)
                        if (child != null) pending.addLast(child)
                    }
                }

                is Iterable<*> -> {
                    if (!visitedContainers.add(value)) continue
                    value.forEach { child -> if (child != null) pending.addLast(child) }
                }

                is Array<*> -> {
                    if (!visitedContainers.add(value)) continue
                    value.forEach { child -> if (child != null) pending.addLast(child) }
                }

                is JSONObject -> {
                    if (!visitedContainers.add(value)) continue
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        pending.addLast(key)
                        val child = value.opt(key)
                        if (child != null && child !== JSONObject.NULL) pending.addLast(child)
                    }
                }

                is JSONArray -> {
                    if (!visitedContainers.add(value)) continue
                    for (index in 0 until value.length()) {
                        val child = value.opt(index)
                        if (child != null && child !== JSONObject.NULL) pending.addLast(child)
                    }
                }
            }
        }
    }

    /**
     * Checks both token-based and line-based views because [parseProxies] evaluates both forms.
     * The decoded text is counted once; individual field checks do not double-count the same data.
     */
    fun validateLinkText(text: String) {
        recordDecodedText(text)

        var tokenCount = 0
        var tokenStart = -1
        var lineCount = 0
        var lineStart = 0

        fun finishToken(endExclusive: Int) {
            if (tokenStart < 0) return
            tokenCount++
            requireProfileCount(tokenCount)
            recordNode()
            recordField(text.substring(tokenStart, endExclusive), includeInDecodedBytes = false)
            tokenStart = -1
        }

        fun finishLine(endExclusive: Int) {
            val line = text.substring(lineStart, endExclusive).trim()
            if (line.isNotEmpty()) {
                lineCount++
                requireProfileCount(lineCount)
                recordNode()
                recordField(line, includeInDecodedBytes = false)
            }
        }

        text.forEachIndexed { index, character ->
            if (character.isWhitespace()) {
                finishToken(index)
            } else if (tokenStart < 0) {
                tokenStart = index
            }

            if (character == '\n') {
                finishLine(index)
                lineStart = index + 1
            }
        }
        finishToken(text.length)
        finishLine(text.length)
    }

    /**
     * Bounds JSON nesting and decoded-node growth before [org.json.JSONTokener] starts its
     * recursive descent parser. For a valid container root, one root node plus object separators,
     * item separators, and the first item in every non-empty container equals the decoded tree's
     * node count. Android's lenient comments, quotes, '=' separators, and ';' item separators are
     * handled without treating their contents as structure.
     */
    fun validateJsonNesting(text: String) {
        data class Container(val closing: Char, var hasItem: Boolean = false)

        val containers = ArrayDeque<Container>()
        var quote = '\u0000'
        var escaped = false
        var inLineComment = false
        var inBlockComment = false
        // JSONTokener strips a leading BOM before parsing, so the preflight must do the same.
        var index = if (text.startsWith('\uFEFF')) 1 else 0

        fun markContainerNonEmpty() {
            val container = containers.lastOrNull() ?: return
            if (!container.hasItem) {
                container.hasItem = true
                recordNode()
            }
        }

        while (index < text.length) {
            val character = text[index]
            val next = text.getOrNull(index + 1)

            when {
                quote != '\u0000' -> {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == quote -> quote = '\u0000'
                    }
                }

                inLineComment -> {
                    if (character == '\n' || character == '\r') inLineComment = false
                }

                inBlockComment -> {
                    if (character == '*' && next == '/') {
                        inBlockComment = false
                        index++
                    }
                }

                character == '"' || character == '\'' -> {
                    if (containers.isEmpty()) return
                    markContainerNonEmpty()
                    quote = character
                }

                character == '#' -> inLineComment = true
                character == '/' && next == '/' -> {
                    inLineComment = true
                    index++
                }

                character == '/' && next == '*' -> {
                    inBlockComment = true
                    index++
                }

                character == '{' || character == '[' -> {
                    if (containers.isEmpty()) {
                        recordNode()
                    } else {
                        markContainerNonEmpty()
                    }
                    containers.addLast(Container(if (character == '{') '}' else ']'))
                    if (containers.size > maxNestingDepth) {
                        throw SubscriptionParseLimitException(
                            "Subscription JSON exceeds nesting depth $maxNestingDepth",
                        )
                    }
                }

                character == '}' || character == ']' -> {
                    val container = containers.lastOrNull() ?: return
                    if (character != container.closing) return
                    containers.removeLast()
                    if (containers.isEmpty()) return
                }

                containers.isEmpty() && !character.isWhitespace() -> return

                character == ':' || character == '=' -> {
                    markContainerNonEmpty()
                    recordNode()
                }

                character == ',' || character == ';' -> {
                    markContainerNonEmpty()
                    recordNode()
                }

                !character.isWhitespace() -> markContainerNonEmpty()
            }
            index++
        }
    }

    /** Performs a linear WireGuard/INI budget pass before ini4j materializes sections and maps. */
    fun validateIniText(text: String) {
        recordDecodedText(text)
        var peerSections = 0
        val logicalLine = StringBuilder()

        fun validateLogicalLine(line: String) {
            recordField(line, includeInDecodedBytes = false)
            if (line.startsWith('[') && line.endsWith(']')) {
                recordNode()
                val sectionName = line.substring(1, line.length - 1).javaTrim()
                recordField(sectionName, includeInDecodedBytes = false)
                if (sectionName == "Peer") {
                    peerSections++
                    requireProfileCount(peerSections)
                }
                return
            }

            val equals = line.indexOf('=')
            val colon = line.indexOf(':')
            val separator = when {
                equals < 0 -> colon
                colon < 0 -> equals
                else -> minOf(equals, colon)
            }
            if (separator >= 0) {
                recordNode(2)
                recordField(
                    line.substring(0, separator).javaTrim(),
                    includeInDecodedBytes = false,
                )
                recordField(
                    line.substring(separator + 1).javaTrim(),
                    includeInDecodedBytes = false,
                )
            } else {
                // ini4j can treat indented lines as continuations. Count them conservatively.
                recordNode()
            }
        }

        fun appendLogicalLine(line: String, endExclusive: Int) {
            val appendedCharacters = endExclusive
            if (
                appendedCharacters > maxFieldBytes ||
                logicalLine.length > maxFieldBytes - appendedCharacters
            ) {
                throw SubscriptionParseLimitException(
                    "Subscription field exceeds $maxFieldBytes bytes",
                )
            }
            logicalLine.append(line, 0, endExclusive)
        }

        for (rawLine in text.lineSequence()) {
            // ini4j uses String.trim(), whose whitespace definition is limited to U+0020.
            val line = rawLine.javaTrim()
            if (line.isEmpty()) continue

            // A comment marker only starts a comment when no escaped logical line is pending.
            if (logicalLine.isEmpty() && (line.startsWith(';') || line.startsWith('#'))) continue

            var endingEscapes = 0
            var escapeIndex = line.lastIndex
            while (escapeIndex >= 0 && line[escapeIndex] == '\\') {
                endingEscapes++
                escapeIndex--
            }
            val continues = (endingEscapes and 1) == 1
            appendLogicalLine(line, line.length - if (continues) 1 else 0)

            if (!continues) {
                validateLogicalLine(logicalLine.toString())
                logicalLine.clear()
            }
        }
    }

    /** Mirrors java.lang.String.trim(), which ini4j applies to every physical INI line. */
    private fun String.javaTrim(): String {
        var start = 0
        var end = length
        while (start < end && this[start] <= ' ') start++
        while (end > start && this[end - 1] <= ' ') end--
        return if (start == 0 && end == length) this else substring(start, end)
    }

    private fun recordDecodedBytes(count: Int) {
        decodedBytes += count.toLong()
        if (decodedBytes > maxDecodedBytes) {
            throw SubscriptionParseLimitException(
                "Subscription decoded data exceeds $maxDecodedBytes bytes",
            )
        }
    }
}
