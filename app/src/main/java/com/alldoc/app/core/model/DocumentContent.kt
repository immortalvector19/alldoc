package com.alldoc.app.core.model

sealed interface DocumentContent {
    val uriString: String
    val format: DocumentFormat
}

data class PdfDocumentModel(
    override val uriString: String,
    val pageCount: Int,
    val annotations: DocumentAnnotations = DocumentAnnotations(),
    val isFormFillable: Boolean = false
) : DocumentContent {
    override val format: DocumentFormat = DocumentFormat.PDF
}

data class WordParagraph(
    val id: String = java.util.UUID.randomUUID().toString(),
    val runs: List<WordTextRun> = emptyList(),
    val headingLevel: Int? = null, // 1 = H1, 2 = H2, null = normal text
    val isListItem: Boolean = false,
    val listLevel: Int = 0
) {
    val fullText: String
        get() = runs.joinToString("") { it.text }
}

data class WordTextRun(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikeThrough: Boolean = false,
    val fontSizePt: Float? = null,
    val colorHex: String? = null
)

data class WordTable(
    val id: String = java.util.UUID.randomUUID().toString(),
    val rows: List<WordTableRow> = emptyList()
)

data class WordTableRow(
    val cells: List<WordTableCell> = emptyList()
)

data class WordTableCell(
    val text: String = "",
    val isHeader: Boolean = false
)

data class WordDocumentModel(
    override val uriString: String,
    val paragraphs: List<WordParagraph> = emptyList(),
    val tables: List<WordTable> = emptyList(),
    val uneditedXmlParts: Map<String, ByteArray> = emptyMap()
) : DocumentContent {
    override val format: DocumentFormat = DocumentFormat.DOCX

    val rawText: String
        get() = paragraphs.joinToString("\n") { it.fullText }
}

data class SpreadsheetDocumentModel(
    override val uriString: String,
    val workbook: SpreadsheetWorkbook = SpreadsheetWorkbook(),
    val uneditedZipParts: Map<String, ByteArray> = emptyMap(),
    val sheetPartPaths: Map<Int, String> = emptyMap()
) : DocumentContent {
    override val format: DocumentFormat = DocumentFormat.XLSX
}

data class PresentationSlide(
    val slideNumber: Int,
    val title: String = "",
    val textBlocks: List<String> = emptyList(),
    val notes: String = ""
)

data class PresentationDocumentModel(
    override val uriString: String,
    val slides: List<PresentationSlide> = emptyList()
) : DocumentContent {
    override val format: DocumentFormat = DocumentFormat.PPTX
}

data class TextDocumentModel(
    override val uriString: String,
    override val format: DocumentFormat,
    val text: String = "",
    val lineCount: Int = text.lines().size
) : DocumentContent

data class ImageDocumentModel(
    override val uriString: String,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "image/jpeg"
) : DocumentContent {
    override val format: DocumentFormat = DocumentFormat.IMAGE_JPEG
}
