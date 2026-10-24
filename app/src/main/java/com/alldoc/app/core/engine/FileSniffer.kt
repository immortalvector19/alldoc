package com.alldoc.app.core.engine

import com.alldoc.app.core.model.DocumentFormat
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

object FileSniffer {

    private val PDF_MAGIC = byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte()) // %PDF
    private val ZIP_MAGIC_1 = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte()) // PK\x03\x04
    private val ZIP_MAGIC_2 = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x05.toByte(), 0x06.toByte()) // PK\x05\x06 (empty zip)
    private val ZIP_MAGIC_3 = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x07.toByte(), 0x08.toByte()) // PK\x07\x08 (spanned zip)
    private val OLE2_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    ) // D0 CF 11 E0 A1 B1 1A E1
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    private val GIF_MAGIC = "GIF8".toByteArray(StandardCharsets.US_ASCII)
    private val RTF_MAGIC = "{\\rtf".toByteArray(StandardCharsets.US_ASCII)

    /**
     * Sniffs the real format of an InputStream using magic bytes, with fallback to filename/MIME.
     * The input stream must support mark/reset or be wrapped in a BufferedInputStream.
     */
    fun sniffFormat(
        inputStream: InputStream,
        filename: String? = null,
        mimeType: String? = null
    ): DocumentFormat {
        val buffered = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)
        buffered.mark(4096)
        val header = ByteArray(4096)
        val readCount = readFully(buffered, header)
        buffered.reset()

        if (readCount <= 0) {
            return DocumentFormat.fromMimeType(mimeType, filename)
        }

        // 1. Sniff PDF: %PDF in first 1024 bytes
        if (containsSequence(header, readCount, PDF_MAGIC, maxOffset = 1024)) {
            return DocumentFormat.PDF
        }

        // 2. Sniff ZIP (OOXML or generic zip)
        if (matchesPrefix(header, ZIP_MAGIC_1) || matchesPrefix(header, ZIP_MAGIC_2) || matchesPrefix(header, ZIP_MAGIC_3)) {
            val ooxmlFormat = sniffZipContents(buffered)
            buffered.reset()
            if (ooxmlFormat != null) {
                return ooxmlFormat
            }
            // Check extension if OOXML inner entries were not immediately detected
            val fromExt = DocumentFormat.fromExtension(filename?.substringAfterLast('.', ""))
            return if (fromExt in listOf(DocumentFormat.DOCX, DocumentFormat.XLSX, DocumentFormat.PPTX)) {
                fromExt
            } else {
                DocumentFormat.UNKNOWN
            }
        }

        // 3. Sniff OLE2 Legacy Microsoft Office (DOC, XLS, PPT)
        if (matchesPrefix(header, OLE2_MAGIC)) {
            val ext = filename?.substringAfterLast('.', "")?.lowercase()
            return when (ext) {
                "xls" -> DocumentFormat.XLS
                "ppt" -> DocumentFormat.PPT
                "doc" -> DocumentFormat.DOC
                else -> {
                    // Try to sniff OLE2 stream names from header buffer
                    val headerStr = String(header, 0, readCount, StandardCharsets.ISO_8859_1)
                    when {
                        headerStr.contains("Workbook") || headerStr.contains("Book") -> DocumentFormat.XLS
                        headerStr.contains("PowerPoint") || headerStr.contains("Current User") -> DocumentFormat.PPT
                        headerStr.contains("WordDocument") -> DocumentFormat.DOC
                        else -> DocumentFormat.DOC // default legacy OLE2
                    }
                }
            }
        }

        // 4. Sniff Images
        if (matchesPrefix(header, JPEG_MAGIC)) return DocumentFormat.IMAGE_JPEG
        if (matchesPrefix(header, PNG_MAGIC)) return DocumentFormat.IMAGE_PNG
        if (isWebP(header, readCount)) return DocumentFormat.IMAGE_WEBP
        if (matchesPrefix(header, GIF_MAGIC)) return DocumentFormat.IMAGE_JPEG // Image viewer renders gif/jpeg

        // 5. Sniff RTF
        if (matchesPrefix(header, RTF_MAGIC)) return DocumentFormat.RTF

        // 6. Sniff Text vs Binary
        val isText = isTextBuffer(header, readCount)
        if (isText) {
            val ext = filename?.substringAfterLast('.', "")?.lowercase()
            return when {
                ext == "csv" || isLikelyCsv(header, readCount) -> DocumentFormat.CSV
                ext == "md" || ext == "markdown" -> DocumentFormat.MARKDOWN
                ext == "rtf" -> DocumentFormat.RTF
                else -> DocumentFormat.TXT
            }
        }

        // Binary file that does not match any known format -> UNKNOWN (NOT plain text!)
        return DocumentFormat.UNKNOWN
    }

    private fun sniffZipContents(inputStream: InputStream): DocumentFormat? {
        return try {
            val zipIn = ZipInputStream(inputStream)
            var entry = zipIn.nextEntry
            var format: DocumentFormat? = null
            var count = 0
            while (entry != null && count < 25) {
                val name = entry.name
                if (name.startsWith("word/") || name.endsWith("document.xml")) {
                    format = DocumentFormat.DOCX
                    break
                } else if (name.startsWith("xl/") || name.endsWith("workbook.xml")) {
                    format = DocumentFormat.XLSX
                    break
                } else if (name.startsWith("ppt/") || name.endsWith("presentation.xml")) {
                    format = DocumentFormat.PPTX
                    break
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
                count++
            }
            format
        } catch (_: Exception) {
            null
        }
    }

    private fun isWebP(bytes: ByteArray, length: Int): Boolean {
        if (length < 12) return false
        val riff = bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
        val webp = bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        return riff && webp
    }

    private fun isTextBuffer(bytes: ByteArray, length: Int): Boolean {
        if (length == 0) return true
        var nonPrintable = 0
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            // Null bytes are a strong indicator of binary data
            if (b == 0) return false
            // Check non-printable control characters (excluding \t=9, \n=10, \r=13)
            if (b < 32 && b != 9 && b != 10 && b != 13) {
                nonPrintable++
            }
        }
        // If more than 5% non-printable characters, it's binary
        return (nonPrintable.toFloat() / length) < 0.05f
    }

    private fun isLikelyCsv(bytes: ByteArray, length: Int): Boolean {
        val sample = String(bytes, 0, minOf(length, 1024), StandardCharsets.UTF_8)
        val lines = sample.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return false
        val commaCountFirst = lines[0].count { it == ',' }
        val commaCountSecond = lines[1].count { it == ',' }
        return commaCountFirst > 1 && commaCountFirst == commaCountSecond
    }

    private fun matchesPrefix(target: ByteArray, prefix: ByteArray): Boolean {
        if (target.size < prefix.size) return false
        for (i in prefix.indices) {
            if (target[i] != prefix[i]) return false
        }
        return true
    }

    private fun containsSequence(target: ByteArray, targetLen: Int, sequence: ByteArray, maxOffset: Int): Boolean {
        val limit = minOf(targetLen - sequence.size, maxOffset)
        for (i in 0..limit) {
            var match = true
            for (j in sequence.indices) {
                if (target[i + j] != sequence[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = inputStream.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }
}
