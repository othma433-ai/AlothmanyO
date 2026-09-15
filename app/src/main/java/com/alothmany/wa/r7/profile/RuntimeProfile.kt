package com.alothmany.wa.r7.profile

import java.security.MessageDigest

/** Versioned identity for one observable WhatsApp runtime surface. */
data class RuntimeProfileKey(
    val packageName: String,
    val appVersion: String,
    val localeTag: String,
    val androidVersion: String,
    val deviceClass: String,
    val instanceIdentity: String
) {
    fun stableId(): String = sha256(
        listOf(packageName, appVersion, localeTag, androidVersion, deviceClass, instanceIdentity)
            .joinToString("\u001f")
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

enum class ProfileHintType {
    GROUP_FILTER_VIEW_ID,
    LIST_CONTAINER_VIEW_ID,
    HEADER_BOUNDS,
    SCROLL_STRATEGY,
    LABEL_VARIANT,
    SCREEN_SIGNATURE,
    ADAPTER_CONFIDENCE
}

data class RuntimeProfileHint(
    val type: ProfileHintType,
    val value: String,
    val verifiedSuccesses: Int,
    val verifiedFailures: Int,
    val confidence: Double,
    val lastSeenAt: Long
)

data class RuntimeProfile(
    val key: RuntimeProfileKey,
    val hints: List<RuntimeProfileHint>,
    val lastSeenAt: Long
)

data class ProfileRecommendation(
    val type: ProfileHintType,
    val value: String,
    val confidence: Double,
    val applicability: Double,
    /** Runtime profiles prioritize strategies only; the normal verifier remains mandatory. */
    val requiresVerification: Boolean = true
)

object RuntimeProfileApplicability {
    fun score(stored: RuntimeProfileKey, current: RuntimeProfileKey): Double {
        if (stored == current) return 1.0
        if (stored.packageName != current.packageName) return 0.0
        if (stored.appVersion != current.appVersion) return 0.25
        var score = 0.85
        if (stored.localeTag != current.localeTag) score -= 0.15
        if (stored.androidVersion != current.androidVersion) score -= 0.10
        if (stored.deviceClass != current.deviceClass) score -= 0.10
        if (stored.instanceIdentity != current.instanceIdentity) score -= 0.20
        return score.coerceIn(0.0, 0.85)
    }
}

object RuntimeProfileCodec {
    private val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    private val dec = java.util.Base64.getUrlDecoder()

    fun encode(profile: RuntimeProfile): String = buildString {
        append("R7PROFILE\t1\n")
        append(listOf(
            "KEY",
            b64(profile.key.packageName), b64(profile.key.appVersion), b64(profile.key.localeTag),
            b64(profile.key.androidVersion), b64(profile.key.deviceClass), b64(profile.key.instanceIdentity),
            profile.lastSeenAt
        ).joinToString("\t")).append('\n')
        profile.hints.sortedWith(compareBy<RuntimeProfileHint> { it.type.name }.thenBy { it.value }).forEach { hint ->
            append(listOf(
                "HINT", hint.type.name, b64(hint.value), hint.verifiedSuccesses,
                hint.verifiedFailures, hint.confidence, hint.lastSeenAt
            ).joinToString("\t")).append('\n')
        }
    }

    fun decode(value: String): RuntimeProfile {
        val lines = value.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == "R7PROFILE\t1") { "unsupported runtime profile payload" }
        val keyParts = lines.firstOrNull { it.startsWith("KEY\t") }?.split('\t')
            ?: error("missing profile key")
        require(keyParts.size == 8) { "invalid profile key payload" }
        val key = RuntimeProfileKey(
            packageName = ub64(keyParts[1]),
            appVersion = ub64(keyParts[2]),
            localeTag = ub64(keyParts[3]),
            androidVersion = ub64(keyParts[4]),
            deviceClass = ub64(keyParts[5]),
            instanceIdentity = ub64(keyParts[6])
        )
        val lastSeenAt = keyParts[7].toLong()
        val hints = lines.filter { it.startsWith("HINT\t") }.map { line ->
            val p = line.split('\t')
            require(p.size == 7) { "invalid profile hint payload" }
            RuntimeProfileHint(
                type = ProfileHintType.valueOf(p[1]),
                value = ub64(p[2]),
                verifiedSuccesses = p[3].toInt(),
                verifiedFailures = p[4].toInt(),
                confidence = p[5].toDouble(),
                lastSeenAt = p[6].toLong()
            )
        }
        return RuntimeProfile(key, hints, lastSeenAt)
    }

    private fun b64(value: String): String = enc.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun ub64(value: String): String = String(dec.decode(value), Charsets.UTF_8)
}
