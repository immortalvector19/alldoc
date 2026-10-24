package com.alldoc.app.core.model

enum class DocumentFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String,
    val category: DocumentCategory,
    val isEditable: Boolean
) {
    PDF("pdf", "application/pdf", "PDF Document", DocumentCategory.PDF, true),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word Document", DocumentCategory.WORD, true),
    DOC("doc", "application/msword", "Legacy Word Document", DocumentCategory.WORD, true),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel Spreadsheet", DocumentCategory.EXCEL, true),
    XLS("xls", "application/vnd.ms-excel", "Legacy Excel Spreadsheet", DocumentCategory.EXCEL, true),
    CSV("csv", "text/csv", "Comma Separated Values", DocumentCategory.EXCEL, true),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint Presentation", DocumentCategory.PPT, true),
    PPT("ppt", "application/vnd.ms-powerpoint", "Legacy PowerPoint", DocumentCategory.PPT, true),
    TXT("txt", "text/plain", "Text Document", DocumentCategory.TEXT, true),
    RTF("rtf", "application/rtf", "Rich Text Document", DocumentCategory.TEXT, true),
    MARKDOWN("md", "text/markdown", "Markdown Document", DocumentCategory.TEXT, true),
    IMAGE_JPEG("jpg", "image/jpeg", "JPEG Image", DocumentCategory.IMAGE, false),
    IMAGE_PNG("png", "image/png", "PNG Image", DocumentCategory.IMAGE, false),
    IMAGE_WEBP("webp", "image/webp", "WebP Image", DocumentCategory.IMAGE, false),
    UNKNOWN("bin", "application/octet-stream", "Generic File", DocumentCategory.OTHER, false);

    companion object {
        fun fromExtension(ext: String?): DocumentFormat {
            val normalized = ext?.trim()?.lowercase()?.removePrefix(".") ?: return UNKNOWN
            return entries.firstOrNull { it.extension == normalized } ?: when (normalized) {
                "jpeg" -> IMAGE_JPEG
                "m4a", "text" -> TXT
                else -> UNKNOWN
            }
        }

        fun fromMimeType(mime: String?, filename: String? = null): DocumentFormat {
            if (filename != null) {
                val ext = filename.substringAfterLast('.', "")
                val fromExt = fromExtension(ext)
                if (fromExt != UNKNOWN) return fromExt
            }
            if (mime == null) return UNKNOWN
            return entries.firstOrNull { it.mimeType.equals(mime, ignoreCase = true) }
                ?: when {
                    mime.contains("pdf", ignoreCase = true) -> PDF
                    mime.contains("wordprocessingml", ignoreCase = true) || mime.contains("msword", ignoreCase = true) -> DOCX
                    mime.contains("spreadsheetml", ignoreCase = true) || mime.contains("excel", ignoreCase = true) -> XLSX
                    mime.contains("presentationml", ignoreCase = true) || mime.contains("powerpoint", ignoreCase = true) -> PPTX
                    mime.startsWith("image/", ignoreCase = true) -> IMAGE_JPEG
                    mime.contains("text/markdown", ignoreCase = true) -> MARKDOWN
                    mime.startsWith("text/", ignoreCase = true) -> TXT
                    else -> UNKNOWN
                }
        }
    }
}

enum class DocumentCategory(val label: String) {
    ALL("All"),
    PDF("PDF"),
    WORD("Word"),
    EXCEL("Excel"),
    PPT("Slides"),
    TEXT("Text"),
    SCANNED("Scanned"),
    IMAGE("Images"),
    OTHER("Other")
}
