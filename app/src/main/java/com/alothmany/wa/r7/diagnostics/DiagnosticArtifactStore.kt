package com.alothmany.wa.r7.diagnostics

import android.content.Context
import java.io.File

/** Bounded private-app storage for sanitized diagnostics and deterministic replay fixtures. */
class DiagnosticArtifactStore(
    context: Context,
    private val maxArtifacts: Int = 20
) {
    init { require(maxArtifacts in 2..100) }
    private val dir = File(context.applicationContext.filesDir, "r7-diagnostics").apply { mkdirs() }

    data class SavedArtifact(val diagnosticFile: File, val replayFile: File)

    fun save(bundle: DiagnosticBundle, fixture: ReplayFixture, capturedAt: Long): SavedArtifact {
        val stem = "r7-${capturedAt}-${bundle.runIdHash.take(12)}"
        val diagnostic = File(dir, "$stem.diag")
        val replay = File(dir, "$stem.replay")
        diagnostic.writeText(bundle.toCanonicalText())
        replay.writeText(fixture.encode())
        trim()
        return SavedArtifact(diagnostic, replay)
    }

    fun latest(limit: Int = 10): List<File> = dir.listFiles()
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .take(limit.coerceIn(1, maxArtifacts * 2))

    private fun trim() {
        val stems = dir.listFiles().orEmpty()
            .groupBy { it.name.substringBeforeLast('.') }
            .entries
            .sortedByDescending { (_, files) -> files.maxOfOrNull { it.lastModified() } ?: 0L }
        stems.drop(maxArtifacts).forEach { (_, files) -> files.forEach(File::delete) }
    }
}
