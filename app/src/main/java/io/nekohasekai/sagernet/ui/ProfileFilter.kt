package io.nekohasekai.sagernet.ui

enum class ProfileFilter(val key: String, val regionCode: String? = null) {
    ALL("all"),
    FAVORITES("favorites"),
    RECENT("recent"),
    FAST("fast"),
    HK("hk", "HK"),
    US("us", "US"),
    JP("jp", "JP");

    companion object {
        fun fromKey(key: String): ProfileFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

data class ProfileFilterCounts(
    val all: Int = 0,
    val favorites: Int = 0,
    val recent: Int = 0,
    val fast: Int = 0,
    val hk: Int = 0,
    val us: Int = 0,
    val jp: Int = 0,
)
