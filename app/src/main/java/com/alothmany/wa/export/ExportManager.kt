package com.alothmany.wa.export

import android.content.ContentResolver
import android.net.Uri
import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.LinkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mime: String) {
    CSV("csv", "text/csv"), JSON("json", "application/json"), TXT("txt", "text/plain"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
}

class ExportManager(
    private val resolver: ContentResolver,
    private val db: AppDatabase
) {
    suspend fun exportLinks(uri: Uri, format: ExportFormat) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "w")?.use { out ->
            when (format) {
                ExportFormat.CSV -> writeCsv(out)
                ExportFormat.JSON -> writeJson(out)
                ExportFormat.TXT -> writeTxt(out)
                ExportFormat.XLSX -> writeXlsx(out)
            }
        } ?: error("Cannot open export destination")
    }

    private suspend fun writeCsv(out: OutputStream) {
        BufferedWriter(OutputStreamWriter(out)).use { w ->
            w.appendLine("url,domain,occurrence_count,first_seen,last_seen")
            forEachLink { link ->
                w.append(csv(link.normalizedUrl)).append(',')
                    .append(csv(link.domain)).append(',')
                    .append(link.occurrenceCount.toString()).append(',')
                    .append(csv(Instant.ofEpochMilli(link.firstSeenAt).toString())).append(',')
                    .append(csv(Instant.ofEpochMilli(link.lastSeenAt).toString())).appendLine()
            }
        }
    }

    private suspend fun writeTxt(out: OutputStream) {
        BufferedWriter(OutputStreamWriter(out)).use { w ->
            forEachLink { link -> w.appendLine(link.normalizedUrl) }
        }
    }

    private suspend fun writeJson(out: OutputStream) {
        BufferedWriter(OutputStreamWriter(out)).use { w ->
            w.append('[')
            var first = true
            forEachLink { link ->
                if (!first) w.append(',')
                first = false
                w.append("{\"url\":\"").append(json(link.normalizedUrl))
                    .append("\",\"domain\":\"").append(json(link.domain))
                    .append("\",\"occurrenceCount\":").append(link.occurrenceCount.toString())
                    .append(",\"firstSeen\":\"").append(Instant.ofEpochMilli(link.firstSeenAt).toString())
                    .append("\",\"lastSeen\":\"").append(Instant.ofEpochMilli(link.lastSeenAt).toString())
                    .append("\"}")
            }
            w.append(']')
        }
    }

    private suspend fun writeXlsx(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zipText(zip, "[Content_Types].xml", contentTypes())
            zipText(zip, "_rels/.rels", rootRels())
            zipText(zip, "xl/workbook.xml", workbook())
            zipText(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val w = BufferedWriter(OutputStreamWriter(zip))
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            var row = 1
            writeRow(w, row++, listOf("URL", "Domain", "Occurrences", "First Seen", "Last Seen"))
            forEachLink { link ->
                writeRow(w, row++, listOf(link.normalizedUrl, link.domain, link.occurrenceCount.toString(),
                    Instant.ofEpochMilli(link.firstSeenAt).toString(), Instant.ofEpochMilli(link.lastSeenAt).toString()))
            }
            w.write("</sheetData></worksheet>")
            w.flush()
            zip.closeEntry()
        }
    }

    private suspend fun forEachLink(block: (LinkEntity) -> Unit) {
        var offset = 0
        while (true) {
            val page = db.linkDao().page(500, offset)
            if (page.isEmpty()) return
            page.forEach(block)
            offset += page.size
        }
    }

    private fun writeRow(w: BufferedWriter, row: Int, values: List<String>) {
        w.write("<row r=\"$row\">")
        values.forEachIndexed { i, value ->
            val ref = "${columnName(i + 1)}$row"
            w.write("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")
        }
        w.write("</row>")
    }

    private fun columnName(index: Int): String {
        var n = index
        val out = StringBuilder()
        while (n > 0) { n--; out.append(('A'.code + n % 26).toChar()); n /= 26 }
        return out.reverse().toString()
    }

    private fun zipText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private fun workbook() = """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Links" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
    private fun csv(s: String) = "\"${s.replace("\"", "\"\"")}\""
    private fun json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
