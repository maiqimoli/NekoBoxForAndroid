package io.nekohasekai.sagernet.utils

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import java.util.Locale
import java.util.TimeZone

object AutoRegionManager {

    private const val PERMISSION_SET_TIME_ZONE = "android.permission.SET_TIME_ZONE"
    private var missingPermissionLogged = false

    internal data class RegionTarget(
        val code: String,
        val timeZone: String,
        val cjkTokens: List<String>,
        val keywords: List<String>,
    )

    internal val targets = listOf(
        RegionTarget(
            "CN", "Asia/Shanghai",
            listOf("中国", "大陆", "内地", "上海", "北京", "广州", "深圳"),
            listOf("china", "mainland", "shanghai", "beijing", ".cn.", "cn-")
        ),
        RegionTarget(
            "HK", "Asia/Hong_Kong",
            listOf("香港"),
            listOf("hong kong", "hongkong", ".hk.", "hk-")
        ),
        RegionTarget(
            "TW", "Asia/Taipei",
            listOf("台湾", "台灣", "台北"),
            listOf("taiwan", "taipei", ".tw.", "tw-")
        ),
        RegionTarget(
            "US", "America/New_York",
            listOf("美国", "美國", "纽约", "紐約"),
            listOf("united states", "america", "usa", "new york", "nyc", ".us.", "us-")
        ),
        RegionTarget(
            "US-WEST", "America/Los_Angeles",
            listOf("洛杉矶", "洛杉磯", "西雅图", "西雅圖"),
            listOf("los angeles", "california", "san jose", "seattle", "la-", "lax")
        ),
        RegionTarget(
            "ID", "Asia/Jakarta",
            listOf("印度尼西亚", "印度尼西亞", "印尼", "雅加达", "雅加達"),
            listOf("indonesia", "jakarta", ".id.", "id-")
        ),
        RegionTarget(
            "IN", "Asia/Kolkata",
            listOf("印度", "孟买", "孟買", "德里"),
            listOf("india", "mumbai", "delhi", ".in.", "in-")
        ),
        RegionTarget(
            "JP", "Asia/Tokyo",
            listOf("日本", "东京", "東京", "大阪"),
            listOf("japan", "tokyo", "osaka", ".jp.", "jp-")
        ),
        RegionTarget(
            "KR", "Asia/Seoul",
            listOf("韩国", "韓國", "首尔", "首爾"),
            listOf("korea", "seoul", ".kr.", "kr-")
        ),
        RegionTarget(
            "SG", "Asia/Singapore",
            listOf("新加坡", "狮城", "獅城"),
            listOf("singapore", ".sg.", "sg-")
        ),
        RegionTarget(
            "TH", "Asia/Bangkok",
            listOf("泰国", "泰國", "曼谷"),
            listOf("thailand", "bangkok", ".th.", "th-")
        ),
        RegionTarget(
            "MY", "Asia/Kuala_Lumpur",
            listOf("马来西亚", "馬來西亞", "吉隆坡"),
            listOf("malaysia", "kuala lumpur", ".my.", "my-")
        ),
        RegionTarget(
            "VN", "Asia/Ho_Chi_Minh",
            listOf("越南", "河内", "河內", "胡志明"),
            listOf("vietnam", "hanoi", "ho chi minh", ".vn.", "vn-")
        ),
        RegionTarget(
            "PH", "Asia/Manila",
            listOf("菲律宾", "菲律賓", "马尼拉", "馬尼拉"),
            listOf("philippines", "manila", ".ph.", "ph-")
        ),
        RegionTarget(
            "GB", "Europe/London",
            listOf("英国", "英國", "伦敦", "倫敦"),
            listOf("united kingdom", "britain", "london", ".uk.", "uk-", ".gb.", "gb-")
        ),
        RegionTarget(
            "NL", "Europe/Amsterdam",
            listOf("荷兰", "荷蘭", "阿姆斯特丹"),
            listOf("netherlands", "amsterdam", ".nl.", "nl-")
        ),
        RegionTarget(
            "DE", "Europe/Berlin",
            listOf("德国", "德國", "法兰克福", "法蘭克福"),
            listOf("germany", "frankfurt", "berlin", ".de.", "de-")
        ),
        RegionTarget(
            "FR", "Europe/Paris",
            listOf("法国", "法國", "巴黎"),
            listOf("france", "paris", ".fr.", "fr-")
        ),
        RegionTarget(
            "CA", "America/Toronto",
            listOf("加拿大", "多伦多", "多倫多"),
            listOf("canada", "toronto", "vancouver", ".ca.", "ca-")
        ),
        RegionTarget(
            "AU", "Australia/Sydney",
            listOf("澳大利亚", "澳大利亞", "澳洲", "悉尼"),
            listOf("australia", "sydney", "melbourne", ".au.", "au-")
        ),
    )

    fun apply(context: Context, profile: ProxyEntity) {
        if (!DataStore.autoRegionTimeZone) return

        val exitProfile = resolveEffectiveFinalExit(profile)
        val target = match(exitProfile) ?: match(profile)
        if (target == null) {
            Logs.i("Auto region skipped: no region matched for ${profile.displayName()}")
            return
        }

        if (TimeZone.getDefault().id != target.timeZone) {
            if (!canSetTimeZone(context)) {
                if (!missingPermissionLogged) {
                    missingPermissionLogged = true
                    Logs.i("Auto region time zone skipped: missing SET_TIME_ZONE permission")
                }
                return
            }
            try {
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarm.setTimeZone(target.timeZone)
                Logs.i("Auto region time zone: ${target.timeZone} by ${target.code}")
            } catch (e: Throwable) {
                Logs.w("Auto region time zone failed: ${target.timeZone}", e)
            }
        }
    }

    private fun canSetTimeZone(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(PERMISSION_SET_TIME_ZONE) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun resolveEffectiveFinalExit(profile: ProxyEntity): ProxyEntity {
        val landingProfile = SagerDatabase.groupDao.getById(profile.groupId)
            ?.landingProxy
            ?.takeIf { it >= 0L }
            ?.let { SagerDatabase.proxyDao.getById(it) }
        val candidate = landingProfile ?: profile
        return resolveFinalExit(candidate, hashSetOf()) ?: candidate
    }

    private fun resolveFinalExit(profile: ProxyEntity, seen: HashSet<Long>): ProxyEntity? {
        if (!seen.add(profile.id)) return null
        if (profile.type != ProxyEntity.TYPE_CHAIN) return profile

        val ids = profile.chainBean?.proxies.orEmpty()
        for (id in ids.asReversed()) {
            val entity = SagerDatabase.proxyDao.getById(id) ?: continue
            resolveFinalExit(entity, seen)?.let { return it }
        }
        return null
    }

    private fun match(profile: ProxyEntity): RegionTarget? {
        val bean = profile.requireBean()
        val texts = listOf(
            profile.displayName(),
            profile.displayAddress(),
            bean.serverAddress ?: "",
        ).filter { it.isNotBlank() }

        matchLastCjkToken(texts)?.let { return it }
        return matchKeyword(texts)
    }

    internal fun matchLastCjkToken(texts: List<String>): RegionTarget? {
        data class Match(val index: Int, val length: Int, val target: RegionTarget)

        var best: Match? = null
        for (text in texts) {
            for (target in targets) {
                for (token in target.cjkTokens) {
                    val index = text.lastIndexOf(token)
                    val currentBest = best
                    if (index >= 0 && (
                                currentBest == null ||
                                        index > currentBest.index ||
                                        index == currentBest.index && token.length > currentBest.length
                                )
                    ) {
                        best = Match(index, token.length, target)
                    }
                }
            }
        }
        return best?.target
    }

    internal fun matchKeyword(texts: List<String>): RegionTarget? {
        val normalized = texts.joinToString(" ") { it.lowercase(Locale.US) }
        return targets.firstOrNull { target ->
            target.keywords.any { keyword -> normalized.contains(keyword) }
        }
    }
}
