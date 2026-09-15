package com.alothmany.wa.domain

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlNormalizer {
    private val urlRegex = Regex("""https?://[^\s<>\"'\[\]{}]+""", RegexOption.IGNORE_CASE)
    private val trackingKeys = setOf("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid")
    private val trailingPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun extract(text: String): List<String> = urlRegex.findAll(text)
        .mapNotNull { match -> normalize(match.value.trimEnd(*trailingPunctuation)) }
        .distinct()
        .toList()

    fun normalize(raw: String): String? {
        return runCatching {
            val candidate = raw.trim().trimEnd(*trailingPunctuation)
            val input = URI(candidate)
            val scheme = input.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = input.host?.lowercase()?.removeSuffix(".") ?: return null
            val port = when {
                input.port == -1 -> -1
                scheme == "https" && input.port == 443 -> -1
                scheme == "http" && input.port == 80 -> -1
                else -> input.port
            }
            val path = URI(null, null, if (input.path.isNullOrBlank()) "/" else input.path, null).normalize().path
                .let { normalized ->
                    when {
                        normalized == "/" -> ""
                        normalized.length > 1 && normalized.endsWith("/") -> normalized.dropLast(1)
                        else -> normalized
                    }
                }
            val query = normalizeQuery(input.rawQuery)
            URI(scheme, null, host, port, path, query, null).toASCIIString()
        }.getOrNull()
    }

    private fun normalizeQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null
        val pairs = rawQuery.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val idx = part.indexOf('=')
            val keyRaw = if (idx >= 0) part.substring(0, idx) else part
            val valueRaw = if (idx >= 0) part.substring(idx + 1) else ""
            val key = decode(keyRaw)
            if (key.startsWith("utm_", ignoreCase = true) || key.lowercase() in trackingKeys) return@mapNotNull null
            key to decode(valueRaw)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        if (pairs.isEmpty()) return null
        return pairs.joinToString("&") { (k, v) ->
            val ek = encode(k)
            if (v.isEmpty()) ek else "$ek=${encode(v)}"
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
