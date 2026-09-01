package io.nekohasekai.sagernet.ui

internal data class ScannerImageDecodePlan(
    val sampleSize: Int,
    val targetWidth: Int,
    val targetHeight: Int,
)

internal object ScannerImageImportPolicy {
    const val MAX_SELECTED_IMAGES = 16
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 40_000_000L
    const val MAX_DECODED_EDGE = 2048

    fun requireSelectionCount(count: Int) {
        require(count in 0..MAX_SELECTED_IMAGES) {
            "Too many images selected: $count (limit $MAX_SELECTED_IMAGES)"
        }
    }

    fun createDecodePlan(width: Int, height: Int): ScannerImageDecodePlan {
        require(width > 0 && height > 0) { "Invalid image dimensions: ${width}x$height" }
        val pixels = width.toLong() * height.toLong()
        require(pixels <= MAX_SOURCE_PIXELS) {
            "Image dimensions are too large: ${width}x$height"
        }

        var sampleSize = 1
        while (ceilDiv(maxOf(width, height), sampleSize) > MAX_DECODED_EDGE) {
            sampleSize *= 2
        }
        return ScannerImageDecodePlan(
            sampleSize = sampleSize,
            targetWidth = ceilDiv(width, sampleSize),
            targetHeight = ceilDiv(height, sampleSize),
        )
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}
