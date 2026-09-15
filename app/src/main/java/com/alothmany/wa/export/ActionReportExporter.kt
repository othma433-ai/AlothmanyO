package com.alothmany.wa.export

import android.content.ContentResolver
import android.net.Uri
import com.alothmany.wa.data.AppDatabase
import java.io.OutputStreamWriter

class ActionReportExporter(
    private val resolver: ContentResolver,
    private val db: AppDatabase
) {
    suspend fun exportCsv(uri: Uri, jobId: String) {
        resolver.openOutputStream(uri, "w")?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write("\uFEFF")
                writer.appendLine("ordinal,target,status,phase,result_state,attempts,verification_attempts,detail,evidence")
                var offset = 0
                val pageSize = 250
                while (true) {
                    val page = db.actionItemDao().pageForJob(jobId, pageSize, offset)
                    if (page.isEmpty()) break
                    for (item in page) {
                        writer.appendLine(
                            listOf(
                                item.ordinal.toString(),
                                item.target,
                                item.status,
                                item.phase,
                                item.resultState.orEmpty(),
                                item.attempts.toString(),
                                item.verificationAttempts.toString(),
                                item.detail.orEmpty(),
                                item.lastEvidence.orEmpty()
                            ).joinToString(",", transform = ::csv)
                        )
                    }
                    offset += page.size
                    writer.flush()
                    if (page.size < pageSize) break
                }
            }
        } ?: error("Unable to open action report output")
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
