package io.nekohasekai.sagernet.ktx

private const val REDACTED_LOG_VALUE = "[REDACTED]"

private const val SENSITIVE_LOG_KEY =
    "(?:password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|id[_-]?token|uuid|" +
        "private[_-]?key|psk|pre[_-]?shared[_-]?key|api[_-]?key|authorization)"

private val privateKeyBlock = Regex(
    "-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)? PRIVATE KEY-----",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val urlUserInfo = Regex(
    "\\b([a-z][a-z0-9+.-]*://)([^/\\s?#]*@)",
    RegexOption.IGNORE_CASE,
)
private val doubleQuotedSecret = Regex(
    "([\\\"']?$SENSITIVE_LOG_KEY[\\\"']?\\s*[:=]\\s*\\\")((?:\\\\.|[^\\\"\\\\])*)(\\\")",
    RegexOption.IGNORE_CASE,
)
private val singleQuotedSecret = Regex(
    "([\\\"']?$SENSITIVE_LOG_KEY[\\\"']?\\s*[:=]\\s*')((?:\\\\.|[^'\\\\])*)(')",
    RegexOption.IGNORE_CASE,
)
private val unquotedSecret = Regex(
    "(\\b$SENSITIVE_LOG_KEY\\b\\s*[:=]\\s*)(?![\\\"'])([^\\s,;}&]+)",
    RegexOption.IGNORE_CASE,
)
private val authorizationHeader = Regex(
    "(\\bauthorization\\s*:\\s*)[^\\r\\n]+",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
)

fun redactSensitiveData(value: String): String {
    var redacted = privateKeyBlock.replace(value, REDACTED_LOG_VALUE)
    redacted = urlUserInfo.replace(redacted) { match ->
        match.groupValues[1] + REDACTED_LOG_VALUE + "@"
    }
    redacted = authorizationHeader.replace(redacted) { match ->
        match.groupValues[1] + REDACTED_LOG_VALUE
    }
    redacted = doubleQuotedSecret.replace(redacted) { match ->
        match.groupValues[1] + REDACTED_LOG_VALUE + match.groupValues[3]
    }
    redacted = singleQuotedSecret.replace(redacted) { match ->
        match.groupValues[1] + REDACTED_LOG_VALUE + match.groupValues[3]
    }
    return unquotedSecret.replace(redacted) { match ->
        match.groupValues[1] + REDACTED_LOG_VALUE
    }
}
