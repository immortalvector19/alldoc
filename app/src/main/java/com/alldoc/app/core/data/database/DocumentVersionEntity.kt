package com.alldoc.app.core.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "document_versions",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["uriString"],
            childColumns = ["documentUri"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("documentUri"),
        Index("timestamp")
    ]
)
data class DocumentVersionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val timestamp: Long = System.currentTimeMillis(),
    val snapshotFilePath: String,
    val versionNote: String? = null,
    val sizeBytes: Long = 0L
)
