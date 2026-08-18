package io.nekohasekai.sagernet.utils

import android.graphics.drawable.Drawable
import androidx.collection.LruCache

/** Shared, byte-bounded icon cache for the two application-list screens. */
object AppIconCache {
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Drawable>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Drawable): Int {
            val width = value.intrinsicWidth.coerceAtLeast(1)
            val height = value.intrinsicHeight.coerceAtLeast(1)
            return (width.toLong() * height * 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    @Synchronized
    fun getOrPut(packageName: String, loader: () -> Drawable): Drawable =
        cache[packageName] ?: loader().also { cache.put(packageName, it) }

    @Suppress("DEPRECATION")
    @Synchronized
    fun trim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cache.evictAll()
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            cache.trimToSize(MAX_BYTES / 2)
        }
    }
}
