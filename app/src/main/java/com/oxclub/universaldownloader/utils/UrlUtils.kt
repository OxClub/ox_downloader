package com.oxclub.universaldownloader.utils

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class UrlProblem { EMPTY, UNSUPPORTED_SCHEME, MALFORMED, HAS_CREDENTIALS, LOCAL_TARGET }

sealed interface UrlCheck {
    data class Valid(val url: HttpUrl) : UrlCheck
    data class Invalid(val problem: UrlProblem) : UrlCheck
}

object UrlUtils {
    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val BAD_SCHEMES = Regex(
        "^(javascript|data|mailto|tel|sms|file|content|intent|ftp|ftps|blob|about|market):",
        RegexOption.IGNORE_CASE,
    )

    /** Finds every http(s) URL inside arbitrary text (e.g. shared text), in order, without duplicates. */
    fun extractUrls(text: String): List<String> =
        URL_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ';', ']', '!', '?', '}') }
            .filter { it.length > 8 }
            .distinct()
            .toList()

    fun normalize(input: String, allowLocal: Boolean = false): UrlCheck {
        val t = input.trim()
        if (t.isEmpty()) return UrlCheck.Invalid(UrlProblem.EMPTY)
        if (t.any { it.isWhitespace() }) return UrlCheck.Invalid(UrlProblem.MALFORMED)
        val lower = t.lowercase()
        val withScheme = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> t
            "://" in t -> return UrlCheck.Invalid(UrlProblem.UNSUPPORTED_SCHEME)
            BAD_SCHEMES.containsMatchIn(t) -> return UrlCheck.Invalid(UrlProblem.UNSUPPORTED_SCHEME)
            else -> "https://$t"
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return UrlCheck.Invalid(UrlProblem.MALFORMED)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return UrlCheck.Invalid(UrlProblem.HAS_CREDENTIALS)
        }
        val host = parsed.host
        val plausibleHost = host.contains('.') || host.contains(':') || host.equals("localhost", true)
        if (!plausibleHost) return UrlCheck.Invalid(UrlProblem.MALFORMED)
        if (!allowLocal && HostPolicy.isLocalHostName(host)) return UrlCheck.Invalid(UrlProblem.LOCAL_TARGET)
        return UrlCheck.Valid(parsed.newBuilder().fragment(null).build())
    }

    fun hostOnly(url: String): String =
        url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: ""
}
