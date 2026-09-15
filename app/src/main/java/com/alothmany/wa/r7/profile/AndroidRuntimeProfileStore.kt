package com.alothmany.wa.r7.profile

import android.content.Context

/** Small durable store for learned UI preferences. Payloads contain no message text. */
class AndroidRuntimeProfileStore(context: Context) : RuntimeProfileStore {
    private val prefs = context.applicationContext.getSharedPreferences("r7_runtime_profiles", Context.MODE_PRIVATE)

    override fun load(key: RuntimeProfileKey): RuntimeProfile? = prefs.getString(storageKey(key.stableId()), null)
        ?.let { runCatching { RuntimeProfileCodec.decode(it) }.getOrNull() }

    override fun save(profile: RuntimeProfile) {
        prefs.edit().putString(storageKey(profile.key.stableId()), RuntimeProfileCodec.encode(profile)).apply()
    }

    override fun allForPackage(packageName: String): List<RuntimeProfile> = prefs.all.values
        .asSequence()
        .filterIsInstance<String>()
        .mapNotNull { runCatching { RuntimeProfileCodec.decode(it) }.getOrNull() }
        .filter { it.key.packageName == packageName }
        .toList()

    override fun reset(key: RuntimeProfileKey) {
        prefs.edit().remove(storageKey(key.stableId())).apply()
    }

    fun resetPackage(packageName: String) {
        val editor = prefs.edit()
        prefs.all.forEach { (storageKey, payload) ->
            val profile = (payload as? String)?.let { runCatching { RuntimeProfileCodec.decode(it) }.getOrNull() }
            if (profile?.key?.packageName == packageName) editor.remove(storageKey)
        }
        editor.apply()
    }

    private fun storageKey(stableId: String) = "profile:$stableId"
}
