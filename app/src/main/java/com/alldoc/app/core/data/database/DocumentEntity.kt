package com.alldoc.app.core.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alldoc.app.core.model.Document
import com.alldoc.app.core.model.DocumentFormat

@Entity(
    tableName = "documents",
    indices = [
        Index("lastAccessed"),
        Index("isFavorite"),
        Index("format")
    ]
)
data class DocumentEntity(
    @PrimaryKey
    val uriString: String,
    val name: String,
    val format: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val lastAccessed: Long,
    val isFavorite: Boolean,
    val isEncrypted: Boolean,
    val thumbnailPath: String?,
    val pageCount: Int?,
    val searchContent: String?
) {
    fun toDomainModel(): Document {
        val docFormat = DocumentFormat.entries.firstOrNull { it.name == format } ?: DocumentFormat.UNKNOWN
        return Document(
            uriString = uriString,
            name = name,
            format = docFormat,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            lastAccessed = lastAccessed,
            isFavorite = isFavorite,
            isEncrypted = isEncrypted,
            thumbnailPath = thumbnailPath,
            pageCount = pageCount,
            summarySnippet = searchContent?.take(160)
        )
    }

    companion object {
        fun fromDomain(doc: Document, searchContent: String? = null): DocumentEntity {
            return DocumentEntity(
                uriString = doc.uriString,
                name = doc.name,
                format = doc.format.name,
                sizeBytes = doc.sizeBytes,
                lastModified = doc.lastModified,
                lastAccessed = doc.lastAccessed,
                isFavorite = doc.isFavorite,
                isEncrypted = doc.isEncrypted,
                thumbnailPath = doc.thumbnailPath,
                pageCount = doc.pageCount,
                searchContent = searchContent ?: doc.summarySnippet
            )
        }
    }
}
