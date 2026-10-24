package com.alldoc.app.core.engine.legacy

import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.PresentationDocumentModel
import com.alldoc.app.core.model.PresentationSlide
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.SpreadsheetWorkbook
import com.alldoc.app.core.model.SpreadsheetWorksheet
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.core.model.WordParagraph
import com.alldoc.app.core.model.WordTextRun
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Robust, read-only extractor for legacy Microsoft Office binary formats (Word .doc, Excel .xls, PowerPoint .ppt).
 *
 * WHAT IT EXTRACTS:
 * - Plain text runs and paragraphs from legacy Word (.doc)
 * - Raw string constants, labels, and numbers from legacy Excel (.xls)
 * - Slide titles and text runs from legacy PowerPoint (.ppt)
 *
 * WHAT IT CANNOT EXTRACT (Format Limitations):
 * - Character formatting (fonts, colors, bold/italic attributes)
 * - Complex paragraph layouts, margins, headers and footers
 * - Excel formula definitions and dynamic calculation results
 * - Embedded images, charts, smart art, and vector shapes
 * - VBA macros and legacy form controls
 */
object Ole2Engine {

    private const val MAX_OLE_READ_BYTES = 15 * 1024 * 1024 // 15MB safety cap
    private const val MAX_RUN_LENGTH = 4000 // Prevent single massive run from breaking layout

    /**
     * Extracts text from legacy Word (.doc) files and builds a readable WordDocumentModel.
     */
    fun parseDoc(inputStream: InputStream, uriString: String): WordDocumentModel {
        val bytes = readBoundedBytes(inputStream)
        val textParagraphs = extractTextRuns(bytes)

        val paragraphs = if (textParagraphs.isNotEmpty()) {
            textParagraphs.map { text ->
                WordParagraph(
                    runs = listOf(WordTextRun(text = text))
                )
            }
        } else {
            listOf(
                WordParagraph(
                    runs = listOf(WordTextRun(text = "[Legacy Word Document - No readable text extracted]"))
                )
            )
        }

        return WordDocumentModel(
            uriString = uriString,
            paragraphs = paragraphs
        )
    }

    /**
     * Extracts cells and strings from legacy Excel (.xls) files and builds a readable SpreadsheetDocumentModel.
     */
    fun parseXls(inputStream: InputStream, uriString: String): SpreadsheetDocumentModel {
        val bytes = readBoundedBytes(inputStream)
        val extractedStrings = extractTextRuns(bytes)

        val cells = mutableMapOf<CellCoordinate, CellData>()
        var row = 0
        var col = 0

        for (str in extractedStrings) {
            cells[CellCoordinate(row, col)] = CellData(rawValue = str)
            col++
            if (col >= 5) {
                col = 0
                row++
            }
        }

        val worksheet = SpreadsheetWorksheet(
            name = "Sheet1 (Legacy Preview)",
            cells = cells,
            rowCount = maxOf(row + 1, 20),
            colCount = 10
        )

        return SpreadsheetDocumentModel(
            uriString = uriString,
            workbook = SpreadsheetWorkbook(sheets = listOf(worksheet), activeSheetIndex = 0)
        )
    }

    /**
     * Extracts slide texts from legacy PowerPoint (.ppt) files and builds a PresentationDocumentModel.
     */
    fun parsePpt(inputStream: InputStream, uriString: String): PresentationDocumentModel {
        val bytes = readBoundedBytes(inputStream)
        val extractedStrings = extractTextRuns(bytes)

        val slides = if (extractedStrings.isNotEmpty()) {
            extractedStrings.chunked(5).mapIndexed { idx, chunk ->
                PresentationSlide(
                    slideNumber = idx + 1,
                    title = chunk.firstOrNull() ?: "Slide ${idx + 1}",
                    textBlocks = chunk.drop(1)
                )
            }
        } else {
            listOf(
                PresentationSlide(
                    slideNumber = 1,
                    title = "Legacy Presentation",
                    textBlocks = listOf("[Legacy PowerPoint - Read-Only Preview]")
                )
            )
        }

        return PresentationDocumentModel(
            uriString = uriString,
            slides = slides
        )
    }

    /**
     * Extracts contiguous printable text runs from an OLE2 binary stream.
     * Guaranteed bounds checks prevent hangs or crashes even on corrupt or truncated inputs.
     */
    fun extractTextRuns(bytes: ByteArray): List<String> {
        val len = bytes.size
        if (len < 512) return emptyList()

        val result = mutableListOf<String>()

        // 1. Scan for UTF-16LE text runs (skip 512-byte OLE2 header)
        var i = 512
        var iterations = 0
        val maxIterations = 3_000_000
        val currentUtf16 = StringBuilder()

        while (i < len - 1 && iterations < maxIterations) {
            iterations++
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF

            if (b1 == 0 && (b0 in 32..126 || b0 == 10 || b0 == 13 || b0 == 9)) {
                if (b0 == 10 || b0 == 13) {
                    if (currentUtf16.length >= 3) {
                        val s = currentUtf16.toString().trim()
                        if (isValidTextRun(s)) result.add(s.take(MAX_RUN_LENGTH))
                    }
                    currentUtf16.clear()
                } else {
                    currentUtf16.append(b0.toChar())
                }
                i += 2
            } else {
                if (currentUtf16.length >= 3) {
                    val s = currentUtf16.toString().trim()
                    if (isValidTextRun(s)) result.add(s.take(MAX_RUN_LENGTH))
                }
                currentUtf16.clear()
                i += 2
            }
        }
        if (currentUtf16.length >= 3) {
            val s = currentUtf16.toString().trim()
            if (isValidTextRun(s)) result.add(s.take(MAX_RUN_LENGTH))
        }

        // 2. Scan for 8-bit ASCII / Windows-1252 text runs
        i = 512
        iterations = 0
        val currentAscii = StringBuilder()
        while (i < len && iterations < maxIterations) {
            iterations++
            val b = bytes[i].toInt() and 0xFF
            if (b in 32..126 || b == 10 || b == 13 || b == 9) {
                if (b == 10 || b == 13) {
                    if (currentAscii.length >= 4) {
                        val s = currentAscii.toString().trim()
                        if (isValidTextRun(s)) result.add(s.take(MAX_RUN_LENGTH))
                    }
                    currentAscii.clear()
                } else {
                    currentAscii.append(b.toChar())
                }
            } else {
                if (currentAscii.length >= 4) {
                    val s = currentAscii.toString().trim()
                    if (isValidTextRun(s)) result.add(s.take(MAX_RUN_LENGTH))
                }
                currentAscii.clear()
            }
            i++
        }
        if (currentAscii.length >= 4) {
            val s = currentAscii.toString().trim()
            if (isValidTextRun(s)) result.add(s.take(MAX_RUN_LENGTH))
        }

        val ignoredNames = setOf(
            "WordDocument", "SummaryInformation", "DocumentSummaryInformation",
            "CompObj", "Current User", "PowerPoint Document", "Workbook", "Book"
        )

        return result
            .filter { str -> str.isNotBlank() && str !in ignoredNames && !str.startsWith("\u0001") && !str.startsWith("\u0005") }
            .distinct()
    }

    private fun isValidTextRun(s: String): Boolean {
        if (s.length < 3) return false
        val hasLetterOrDigit = s.any { it.isLetterOrDigit() }
        if (!hasLetterOrDigit) return false
        if (s == "Root Entry" || s.startsWith("Table") || s.startsWith("Data")) return false
        return true
    }

    private fun readBoundedBytes(inputStream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalRead = 0
        var read = inputStream.read(buffer)
        while (read != -1 && totalRead < MAX_OLE_READ_BYTES) {
            out.write(buffer, 0, read)
            totalRead += read
            read = inputStream.read(buffer)
        }
        return out.toByteArray()
    }
}
