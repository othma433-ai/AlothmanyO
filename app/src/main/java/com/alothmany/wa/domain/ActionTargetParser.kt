package com.alothmany.wa.domain

import java.net.URI

object ActionTargetParser {
    fun lines(raw: String): List<String> = raw
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    fun invites(raw: String): List<String> = lines(raw)
        .mapNotNull(::normalizeInvite)
        .distinct()

    private fun normalizeInvite(value: String): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", true)) return null
        if (!uri.host.equals("chat.whatsapp.com", true)) return null
        val path = uri.path?.trim('/')?.takeIf { it.isNotBlank() } ?: return null
        "https://chat.whatsapp.com/$path"
    }.getOrNull()
}
