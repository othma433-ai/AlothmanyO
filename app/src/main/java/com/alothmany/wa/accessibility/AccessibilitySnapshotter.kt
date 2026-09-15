package com.alothmany.wa.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.ArrayDeque

class AccessibilitySnapshotter(
    private val maxNodes: Int = 3500,
    private val maxDepth: Int = 35
) {
    private data class Entry(
        val text: String,
        val bounds: Rect,
        val clickable: Boolean,
        val scrollable: Boolean,
        val className: String
    )

    fun snapshot(root: AccessibilityNodeInfo, screenHeight: Int): WindowSnapshot {
        val entries = mutableListOf<Entry>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++
            val rect = Rect().also(node::getBoundsInScreen)
            val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ").trim()
            if (text.isNotBlank()) {
                entries += Entry(text.take(4000), rect, node.isClickable, node.isScrollable, node.className?.toString().orEmpty())
            }
            if (depth < maxDepth) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }

        val uniqueTexts = entries.map { it.text }.distinct().take(1200)
        val title = entries
            .asSequence()
            .filter { it.bounds.top in 0..(screenHeight * 0.22).toInt() }
            .map { it.text.trim() }
            .filter { it.length in 2..120 }
            .filterNot(::isToolbarNoise)
            .firstOrNull()

        val messageEntries = entries.asSequence()
            .filter { it.bounds.top > (screenHeight * 0.12).toInt() }
            .filter { it.text.length >= 4 }
            .filterNot { isToolbarNoise(it.text) }
            .distinctBy { "${it.text}|${it.bounds.top}|${it.bounds.bottom}" }
            .take(1000)
            .map { entry ->
                MessageSnapshot(
                    text = entry.text,
                    fingerprint = sha256(entry.text)
                )
            }.toList()

        val windowFingerprint = sha256(
            messageEntries.take(120).joinToString("|") { it.fingerprint }
        )
        return WindowSnapshot(
            packageName = root.packageName?.toString().orEmpty(),
            chatTitle = title,
            windowFingerprint = windowFingerprint,
            messages = messageEntries,
            visibleTexts = uniqueTexts
        )
    }

    private fun isToolbarNoise(value: String): Boolean {
        val s = value.trim().lowercase()
        return s in setOf("whatsapp", "search", "بحث", "more options", "خيارات إضافية", "video call", "مكالمة فيديو", "voice call", "مكالمة صوتية")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
