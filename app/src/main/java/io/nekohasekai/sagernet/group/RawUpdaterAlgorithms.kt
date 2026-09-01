package io.nekohasekai.sagernet.group

internal data class DeduplicationResult<T>(
    val unique: List<T>,
    val duplicateNames: List<String>,
)

private data class DeduplicationBucket<T>(
    val item: T,
    val firstName: String,
    val index: Int,
    var firstNameReported: Boolean = false,
)

/**
 * Keeps the first item for every key while retaining the duplicate labels used by the update UI.
 * A key lookup replaces the previous LinkedHashSet.indexOf hot path, making the pass linear on
 * average even for large subscriptions containing many duplicate endpoints.
 */
internal fun <T, K> deduplicateKeepingFirst(
    items: List<T>,
    keySelector: (T) -> K,
    nameSelector: (T) -> String,
): DeduplicationResult<T> {
    val buckets = LinkedHashMap<K, DeduplicationBucket<T>>(items.size)
    val duplicateNames = ArrayList<String>()

    for (item in items) {
        val key = keySelector(item)
        val bucket = buckets[key]
        if (bucket == null) {
            buckets[key] = DeduplicationBucket(
                item = item,
                firstName = nameSelector(item),
                index = buckets.size,
            )
            continue
        }

        if (!bucket.firstNameReported) {
            val firstName = bucket.firstName.replace(" (${bucket.index})", "")
            if (firstName.isNotBlank()) {
                duplicateNames.add("$firstName (${bucket.index})")
            }
            bucket.firstNameReported = true
        }
        duplicateNames.add("${nameSelector(item)} (${bucket.index})")
    }

    return DeduplicationResult(
        unique = buckets.values.map { it.item },
        duplicateNames = duplicateNames,
    )
}

/**
 * Mutates only colliding names. Suffix cursors are remembered per original name so a run of N
 * equal names requires O(N) expected work instead of repeatedly scanning all previous names.
 */
internal fun <T> ensureUniqueNames(
    items: List<T>,
    nameSelector: (T) -> String,
    rename: (T, String) -> Unit,
) {
    val usedNames = HashSet<String>(items.size)
    val nextSuffix = HashMap<String, Int>()

    for (item in items) {
        val originalName = nameSelector(item)
        if (usedNames.add(originalName)) continue

        var suffix = nextSuffix[originalName] ?: 1
        var candidate: String
        do {
            candidate = "$originalName ($suffix)"
            suffix++
        } while (!usedNames.add(candidate))

        nextSuffix[originalName] = suffix
        rename(item, candidate)
    }
}

internal data class UniqueKeyMatch<E, I>(
    val existing: E,
    val incoming: I,
)

/**
 * Matches only keys that occur exactly once on each side. Ambiguous keys remain unmatched so a
 * subscription refresh never transfers an existing entity's identity or traffic arbitrarily.
 */
internal fun <E, I, K : Any> matchUniqueByKey(
    existing: List<E>,
    incoming: List<I>,
    existingKey: (E) -> K?,
    incomingKey: (I) -> K?,
): List<UniqueKeyMatch<E, I>> {
    val existingBuckets = LinkedHashMap<K, MutableList<E>>()
    for (item in existing) {
        val key = existingKey(item) ?: continue
        existingBuckets.getOrPut(key, ::ArrayList).add(item)
    }

    val incomingBuckets = LinkedHashMap<K, MutableList<I>>()
    for (item in incoming) {
        val key = incomingKey(item) ?: continue
        incomingBuckets.getOrPut(key, ::ArrayList).add(item)
    }

    val matches = ArrayList<UniqueKeyMatch<E, I>>()
    for ((key, incomingItems) in incomingBuckets) {
        val existingItems = existingBuckets[key] ?: continue
        if (existingItems.size == 1 && incomingItems.size == 1) {
            matches.add(UniqueKeyMatch(existingItems.single(), incomingItems.single()))
        }
    }
    return matches
}
