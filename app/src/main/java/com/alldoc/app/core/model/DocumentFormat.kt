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
    DOC("doc", "application/msword", "Legacy Word Document (Read-Only)", DocumentCategory.WORD, false),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel Spreadsheet", DocumentCategory.EXCEL, true),
    XLS("xls", "application/vnd.ms-excel", "Legacy Excel Spreadsheet (Read-Only)", DocumentCategory.EXCEL, false),
    CSV("csv", "text/csv", "Comma Separated Values", DocumentCategory.EXCEL, true),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint Presentation", DocumentCategory.PPT, true),
    PPT("ppt", "application/vnd.ms-powerpoint", "Legacy PowerPoint (Read-Only)", DocumentCategory.PPT, false),
    TXT("txt", "text/plain", "Text Document", DocumentCategory.TEXT, true),
    RTF("rtf", "application/rtf", "Rich Text Document", DocumentCategory.TEXT, true),
    MARKDOWN("md", "text/markdown", "Markdown Document", DocumentCategory.TEXT, true),
    IMAGE_JPEG("jpg", "image/jpeg", "JPEG Image", DocumentCategory.IMAGE, false),
    IMAGE_PNG("png", "image/png", "PNG Image", DocumentCategory.IMAGE, false),
    IMAGE_WEBP("webp", "image/webp", "WebP Image", DocumentCategory.IMAGE, false),
    VAULT("vault", "application/x-alldoc-vault", "Encrypted Vault", DocumentCategory.OTHER, false),
    UNKNOWN("bin", "application/octet-stream", "Generic File", DocumentCategory.OTHER, false);

    companion object {
        fun fromExtension(ext: String?): DocumentFormat {
            val normalized = ext?.trim()?.lowercase()?.removePrefix(".") ?: return UNKNOWN
            return entries.firstOrNull { it.extension == normalized } ?: when (normalized) {
                "jpeg" -> IMAGE_JPEG
                "text", "log" -> TXT
                "markdown" -> MARKDOWN
                "enc", "vault" -> VAULT
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
                    mime.contains("vault", ignoreCase = true) -> VAULT
                    mime.contains("pdf", ignoreCase = true) -> PDF
                    mime.contains("wordprocessingml", ignoreCase = true) -> DOCX
                    mime.contains("msword", ignoreCase = true) -> DOC
                    mime.contains("spreadsheetml", ignoreCase = true) -> XLSX
                    mime.contains("ms-excel", ignoreCase = true) || mime.contains("excel", ignoreCase = true) -> XLS
                    mime.contains("csv", ignoreCase = true) || mime.contains("comma-separated", ignoreCase = true) -> CSV
                    mime.contains("presentationml", ignoreCase = true) -> PPTX
                    mime.contains("ms-powerpoint", ignoreCase = true) || mime.contains("powerpoint", ignoreCase = true) -> PPT
                    mime.contains("image/png", ignoreCase = true) -> IMAGE_PNG
                    mime.contains("image/webp", ignoreCase = true) -> IMAGE_WEBP
                    mime.startsWith("image/", ignoreCase = true) -> IMAGE_JPEG
                    mime.contains("markdown", ignoreCase = true) -> MARKDOWN
                    mime.contains("rtf", ignoreCase = true) -> RTF
                    mime.startsWith("text/plain", ignoreCase = true) -> TXT
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
