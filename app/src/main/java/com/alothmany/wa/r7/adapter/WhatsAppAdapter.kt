package com.alothmany.wa.r7.adapter

import com.alothmany.wa.r7.snapshot.ActionTargetEvidence
import com.alothmany.wa.r7.snapshot.NodeSnapshot


data class AdapterMatch(val exact: Boolean, val confidence: Double, val reason: String)

interface WhatsAppAdapter {
    fun packageName(): String?
    fun matchPackage(packageName: String): AdapterMatch
    fun groupFilterLabels(): Set<String>
    fun joinLabels(): Set<String>
    fun requestJoinLabels(): Set<String>

    fun findGroupsFilter(nodes: List<NodeSnapshot>): ActionTargetEvidence? =
        nodes.firstOrNull { node -> node.viewId.lowercase().let { id -> groupFilterLabels().any { it.lowercase() in id } } }
            ?.let(ActionTargetEvidence::from)
}

class ConsumerWhatsAppAdapter : WhatsAppAdapter {
    override fun packageName() = "com.whatsapp"
    override fun matchPackage(packageName: String) = AdapterMatch(packageName == packageName(), if (packageName == packageName()) 1.0 else 0.0, "consumer-package")
    override fun groupFilterLabels() = setOf("groups", "المجموعات")
    override fun joinLabels() = setOf("join group", "join chat", "انضمام إلى المجموعة", "الانضمام إلى المجموعة", "انضمام")
    override fun requestJoinLabels() = setOf("request to join", "طلب الانضمام", "إرسال طلب انضمام")
}

class BusinessWhatsAppAdapter : WhatsAppAdapter {
    override fun packageName() = "com.whatsapp.w4b"
    override fun matchPackage(packageName: String) = AdapterMatch(packageName == packageName(), if (packageName == packageName()) 1.0 else 0.0, "business-package")
    override fun groupFilterLabels() = setOf("groups", "المجموعات")
    override fun joinLabels() = setOf("join group", "join chat", "انضمام إلى المجموعة", "الانضمام إلى المجموعة", "انضمام")
    override fun requestJoinLabels() = setOf("request to join", "طلب الانضمام", "إرسال طلب انضمام")
}

class GenericWhatsAppAdapter : WhatsAppAdapter {
    override fun packageName(): String? = null
    override fun matchPackage(packageName: String) = AdapterMatch(false, if (packageName.isNotBlank()) 0.25 else 0.0, "generic-fallback")
    override fun groupFilterLabels() = setOf("groups", "المجموعات")
    override fun joinLabels() = setOf("join group", "join chat", "انضمام إلى المجموعة", "الانضمام إلى المجموعة", "انضمام")
    override fun requestJoinLabels() = setOf("request to join", "طلب الانضمام", "إرسال طلب انضمام")
}
