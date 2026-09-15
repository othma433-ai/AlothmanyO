package com.alothmany.wa.r7.profile

interface RuntimeProfileStore {
    fun load(key: RuntimeProfileKey): RuntimeProfile?
    fun save(profile: RuntimeProfile)
    fun allForPackage(packageName: String): List<RuntimeProfile>
    fun reset(key: RuntimeProfileKey)
}

class InMemoryRuntimeProfileStore : RuntimeProfileStore {
    private val profiles = linkedMapOf<String, RuntimeProfile>()

    override fun load(key: RuntimeProfileKey): RuntimeProfile? = profiles[key.stableId()]

    override fun save(profile: RuntimeProfile) {
        profiles[profile.key.stableId()] = profile
    }

    override fun allForPackage(packageName: String): List<RuntimeProfile> = profiles.values
        .filter { it.key.packageName == packageName }

    override fun reset(key: RuntimeProfileKey) {
        profiles.remove(key.stableId())
    }
}

class RuntimeProfileRepository(
    private val store: RuntimeProfileStore,
    private val verifiedSuccessThreshold: Int = 3
) {
    init { require(verifiedSuccessThreshold >= 2) }

    fun recordSuccess(
        key: RuntimeProfileKey,
        type: ProfileHintType,
        value: String,
        verified: Boolean,
        now: Long
    ) {
        if (!verified || value.isBlank()) return
        mutate(key, type, value, now) { previous ->
            previous.copy(
                verifiedSuccesses = previous.verifiedSuccesses + 1,
                confidence = confidence(previous.verifiedSuccesses + 1, previous.verifiedFailures),
                lastSeenAt = now
            )
        }
    }

    fun recordFailure(
        key: RuntimeProfileKey,
        type: ProfileHintType,
        value: String,
        now: Long
    ) {
        if (value.isBlank()) return
        mutate(key, type, value, now) { previous ->
            previous.copy(
                verifiedFailures = previous.verifiedFailures + 1,
                confidence = confidence(previous.verifiedSuccesses, previous.verifiedFailures + 1),
                lastSeenAt = now
            )
        }
    }

    fun recommendations(currentKey: RuntimeProfileKey, type: ProfileHintType): List<ProfileRecommendation> {
        val profiles = store.allForPackage(currentKey.packageName)
        return profiles.flatMap { profile ->
            val applicability = RuntimeProfileApplicability.score(profile.key, currentKey)
            profile.hints.asSequence()
                .filter { it.type == type }
                .filter { it.verifiedSuccesses >= verifiedSuccessThreshold }
                .filter { it.confidence > 0.0 && applicability > 0.0 }
                .map { hint ->
                    ProfileRecommendation(
                        type = hint.type,
                        value = hint.value,
                        confidence = hint.confidence,
                        applicability = applicability,
                        requiresVerification = true
                    )
                }.toList()
        }.sortedByDescending { it.confidence * it.applicability }
            .distinctBy { it.value }
    }

    fun reset(key: RuntimeProfileKey) = store.reset(key)

    private fun mutate(
        key: RuntimeProfileKey,
        type: ProfileHintType,
        value: String,
        now: Long,
        transform: (RuntimeProfileHint) -> RuntimeProfileHint
    ) {
        val existing = store.load(key) ?: RuntimeProfile(key, emptyList(), now)
        val hints = existing.hints.toMutableList()
        val index = hints.indexOfFirst { it.type == type && it.value == value }
        val prior = if (index >= 0) hints[index] else RuntimeProfileHint(type, value, 0, 0, 0.0, now)
        val updated = transform(prior)
        if (index >= 0) hints[index] = updated else hints += updated
        store.save(existing.copy(hints = hints, lastSeenAt = now))
    }

    private fun confidence(successes: Int, failures: Int): Double {
        if (successes <= 0) return 0.0
        // Conservative beta-prior: confidence grows slowly and any failure immediately reduces it.
        return (successes.toDouble() / (successes + failures + 2).toDouble()).coerceIn(0.0, 0.95)
    }
}
