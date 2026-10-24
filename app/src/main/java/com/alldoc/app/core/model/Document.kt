package com.alldoc.app.core.model

data class Document(
    val uriString: String,
    val name: String,
    val format: DocumentFormat,
    val sizeBytes: Long = 0L,
    val lastModified: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isEncrypted: Boolean = false,
    val thumbnailPath: String? = null,
    val pageCount: Int? = null,
    val summarySnippet: String? = null
) {
    val formattedSize: String
        get() = when {
            sizeBytes <= 0 -> "0\u00A0B"
            sizeBytes < 1024 -> "$sizeBytes\u00A0B"
            sizeBytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f\u00A0KB", sizeBytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f\u00A0MB", sizeBytes / (1024.0 * 1024.0))
        }
}
