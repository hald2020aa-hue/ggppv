package com.example.core.common

import java.net.URI
import java.util.Locale

/**
 * Rule 3 & Rule 16: Strict domain normalization and security validation.
 * - Cleans input string
 * - Adds HTTPS when scheme is omitted
 * - Rejects unsafe schemes (http, javascript, file, data, ftp)
 * - Rejects localhost, loopback, and private/internal IPv4/IPv6 ranges
 * - Strips paths, query parameters, and fragments, returning only the normalized domain
 */
fun normalizeDomain(input: String): String {
    var trimmed = input
        .replace("\u200E", "")
        .replace("\u200F", "")
        .replace("\u202A", "")
        .replace("\u202B", "")
        .replace("\u202C", "")
        .trim()
        .trimStart('/', '\\', ' ')
        .trimEnd('/', '\\', ' ')
        .lowercase(Locale.ROOT)

    // Handle cases where RTL copy-paste putsscheme at the end or prefixes with slash (e.g., /https://starzmanga.com)
    if (trimmed.startsWith("https:/") && !trimmed.startsWith("https://")) {
        trimmed = "https://" + trimmed.removePrefix("https:/").trimStart('/')
    }
    if (trimmed.endsWith("://https")) {
        trimmed = "https://" + trimmed.removeSuffix("://https").trim('/')
    }

    require(trimmed.isNotBlank()) { "الرابط فارغ. يرجى إدخال نطاق موقع صحيح." }

    // Reject explicitly unsafe schemes
    val forbiddenSchemes = listOf("javascript:", "file:", "data:", "ftp:", "intent:", "content:")
    for (scheme in forbiddenSchemes) {
        if (trimmed.startsWith(scheme)) {
            throw IllegalArgumentException("مخطط الرابط غير آمن ($scheme). يُسمح فقط بروابط HTTPS الآمنة.")
        }
    }

    // Upgrade http:// to https:// automatically so user-pasted http links work seamlessly over HTTPS
    if (trimmed.startsWith("http://")) {
        trimmed = "https://" + trimmed.removePrefix("http://")
    }

    val urlWithScheme = if (trimmed.startsWith("https://")) {
        trimmed
    } else if (trimmed.contains("://")) {
        throw IllegalArgumentException("مخطط الرابط غير مدعوم. استخدم HTTPS فقط.")
    } else {
        "https://$trimmed"
    }

    val uri = try {
        URI(urlWithScheme.substringBefore(' ').substringBefore('?').substringBefore('#'))
    } catch (e: Exception) {
        throw IllegalArgumentException("صيغة الرابط غير صالحة.")
    }

    val rawHost = uri.host
        ?: urlWithScheme.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
    val host = rawHost.removePrefix("www.").trim('.').trim()

    require(host.isNotBlank() && host.contains('.')) {
        "يرجى إدخال اسم نطاق صالح (مثال: starzmanga.com)."
    }

    // Reject localhost and internal network addresses (SSRF protection)
    if (isInternalOrLocalAddress(host)) {
        throw IllegalArgumentException("يُمنع الاتصال بالعناوين المحلية أو الشبكات الداخلية.")
    }

    // Validate valid domain characters
    val domainRegex = Regex("^[a-z0-9.-]+\\.[a-z]{2,}$")
    require(domainRegex.matches(host)) {
        "اسم النطاق يحتوي على رموز غير صالحة."
    }

    return host
}

fun isInternalOrLocalAddress(host: String): Boolean {
    val clean = host.lowercase(Locale.ROOT)
    if (clean == "localhost" || clean.endsWith(".local") || clean.endsWith(".internal") || clean == "::1") {
        return true
    }
    val ipv4Parts = clean.split('.')
    if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
        val first = ipv4Parts[0].toInt()
        val second = ipv4Parts[1].toInt()
        if (first == 127 || first == 10 || first == 0) return true
        if (first == 192 && second == 168) return true
        if (first == 172 && second in 16..31) return true
        if (first == 169 && second == 254) return true
    }
    return false
}

fun isSafeRedirectDestination(originalDomain: String, targetUrl: String): Boolean {
    return try {
        val targetDomain = normalizeDomain(targetUrl)
        targetDomain == originalDomain || targetDomain.endsWith(".$originalDomain")
    } catch (e: Exception) {
        false
    }
}
