package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoRegionManagerTest {

    @Test
    fun cjkTokenMatchesChineseRegionNames() {
        assertEquals("CN", AutoRegionManager.matchLastCjkToken(listOf("上海 电信 01"))?.code)
        assertEquals("HK", AutoRegionManager.matchLastCjkToken(listOf("香港 02 落地"))?.code)
        assertEquals("TW", AutoRegionManager.matchLastCjkToken(listOf("台湾 节点 03"))?.code)
        assertEquals("JP", AutoRegionManager.matchLastCjkToken(listOf("东京 04 线路"))?.code)
        assertEquals("US", AutoRegionManager.matchLastCjkToken(listOf("美国 05 优化"))?.code)
    }

    @Test
    fun cjkTokenPrefersLastOccurrence() {
        // 文本同时含多个地区词时，取最后出现的 token
        val result = AutoRegionManager.matchLastCjkToken(listOf("日本 东京 香港 中转"))
        // "香港" 在 "东京" 之后，应匹配 HK
        assertEquals("HK", result?.code)
    }

    @Test
    fun cjkTokenMatchesTraditionalChinese() {
        assertEquals("TW", AutoRegionManager.matchLastCjkToken(listOf("台灣 節點"))?.code)
        assertEquals("HK", AutoRegionManager.matchLastCjkToken(listOf("香港 節點"))?.code)
    }

    @Test
    fun keywordMatchesEnglishRegionNames() {
        assertEquals("US", AutoRegionManager.matchKeyword(listOf("US-LAX-01"))?.code)
        assertEquals("US-WEST", AutoRegionManager.matchKeyword(listOf("Los Angeles 01"))?.code)
        assertEquals("JP", AutoRegionManager.matchKeyword(listOf("Tokyo-NTT"))?.code)
        assertEquals("SG", AutoRegionManager.matchKeyword(listOf("Singapore-01"))?.code)
        assertEquals("DE", AutoRegionManager.matchKeyword(listOf("Frankfurt 01"))?.code)
    }

    @Test
    fun keywordMatchesDomainStyleNames() {
        assertEquals("HK", AutoRegionManager.matchKeyword(listOf("node.hk.abcd"))?.code)
        assertEquals("TW", AutoRegionManager.matchKeyword(listOf("node-tw-01"))?.code)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(AutoRegionManager.matchLastCjkToken(listOf("unknown server 01")))
        assertNull(AutoRegionManager.matchKeyword(listOf("unknown server 01")))
    }

    @Test
    fun targetsCoverExpectedRegions() {
        val codes = AutoRegionManager.targets.map { it.code }.toSet()
        // 常见地区应已覆盖
        for (expected in listOf("CN", "HK", "TW", "US", "JP", "KR", "SG", "GB", "DE", "FR")) {
            assertNotNull("region $expected not in targets", codes.firstOrNull { it == expected })
        }
    }
}
