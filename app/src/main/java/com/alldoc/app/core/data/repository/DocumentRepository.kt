package com.alldoc.app.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.alldoc.app.core.data.database.AllDocDatabase
import com.alldoc.app.core.data.database.DocumentEntity
import com.alldoc.app.core.data.database.DocumentVersionEntity
import com.alldoc.app.core.engine.FileSniffer
import com.alldoc.app.core.model.Document
import com.alldoc.app.core.model.DocumentCategory
import com.alldoc.app.core.model.DocumentFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DocumentRepository(
    private val context: Context,
    private val database: AllDocDatabase = AllDocDatabase.getInstance(context)
) {
    private val dao = database.documentDao()
    private val contentResolver: ContentResolver = context.contentResolver

    fun getRecentDocuments(): Flow<List<Document>> {
        return dao.getAllRecent().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    fun getFavorites(): Flow<List<Document>> {
        return dao.getFavorites().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    fun getByCategory(category: DocumentCategory): Flow<List<Document>> {
        return dao.getAllRecent().map { entities ->
            val domainDocs = entities.map { it.toDomainModel() }
            if (category == DocumentCategory.ALL) domainDocs
            else domainDocs.filter { it.format.category == category }
        }
    }

    fun search(query: String): Flow<List<Document>> {
        return dao.search(query.trim()).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    suspend fun getDocumentByUri(uriString: String): Document? {
        return dao.getByUri(uriString)?.toDomainModel()
    }

    suspend fun recordDocumentAccess(uri: Uri, searchContent: String? = null): Document = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val existing = dao.getByUri(uriString)

        val metadata = resolveDocumentMetadata(uri)
        val document = if (existing != null) {
            existing.toDomainModel().copy(
                lastAccessed = System.currentTimeMillis(),
                sizeBytes = if (metadata.sizeBytes > 0) metadata.sizeBytes else existing.sizeBytes,
                name = metadata.name.ifEmpty { existing.name },
                format = if (metadata.format != DocumentFormat.UNKNOWN) metadata.format else existing.toDomainModel().format
            )
        } else {
            metadata
        }

        // Preserve existing searchContent if searchContent argument is null
        val finalSearchContent = searchContent ?: existing?.searchContent
        dao.insertOrUpdate(DocumentEntity.fromDomain(document, finalSearchContent))
        document
    }

    suspend fun toggleFavorite(uriString: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        dao.updateFavorite(uriString, isFavorite)
    }

    suspend fun renameDocument(uriString: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        var realRenamed = false
        var finalUriString = uriString

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                val newUri = android.provider.DocumentsContract.renameDocument(contentResolver, uri, newName)
                if (newUri != null) {
                    finalUriString = newUri.toString()
                    realRenamed = true
                }
            } catch (_: Exception) {
                // Provider may not support renameDocument
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val oldFile = uri.path?.let { File(it) }
            if (oldFile != null && oldFile.exists()) {
                val newFile = File(oldFile.parentFile, newName)
                if (oldFile.renameTo(newFile)) {
                    finalUriString = Uri.fromFile(newFile).toString()
                    realRenamed = true
                }
            }
        }

        if (finalUriString != uriString) {
            val existing = dao.getByUri(uriString)
            if (existing != null) {
                dao.deleteByUri(uriString)
                dao.insertOrUpdate(existing.copy(uriString = finalUriString, name = newName, lastModified = System.currentTimeMillis()))
            }
        } else {
            dao.rename(uriString, newName)
        }
        realRenamed
    }

    suspend fun deleteDocument(uriString: String): Boolean = withContext(Dispatchers.IO) {
        // Clean up all snapshot files on disk and in database
        try {
            val versions = dao.getVersionsListForDocument(uriString)
            for (v in versions) {
                val f = File(v.snapshotFilePath)
                if (f.exists()) f.delete()
            }
            dao.deleteVersionsForDocument(uriString)
        } catch (_: Exception) {}

        dao.deleteByUri(uriString)
        var deleted = false
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                try {
                    deleted = android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (_: Exception) {
                    try {
                        val count = contentResolver.delete(uri, null, null)
                        deleted = count > 0
                    } catch (_: Exception) {}
                }
            } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val file = uri.path?.let { File(it) }
                if (file != null && file.exists()) {
                    deleted = file.delete()
                }
            }
        } catch (_: Exception) {
            // External file deletion exception
        }
        deleted
    }

    suspend fun saveSnapshotVersion(uriString: String, file: File, note: String? = null): DocumentVersionEntity = withContext(Dispatchers.IO) {
        val versionsDir = File(context.filesDir, "versions").apply { mkdirs() }
        val filename = "snapshot_${System.currentTimeMillis()}_${file.length()}.bak"
        val snapshotFile = File(versionsDir, filename)

        file.inputStream().use { input ->
            FileOutputStream(snapshotFile).use { output ->
                input.copyTo(output)
            }
        }

        val entity = DocumentVersionEntity(
            documentUri = uriString,
            snapshotFilePath = snapshotFile.absolutePath,
            versionNote = note,
            sizeBytes = file.length()
        )
        dao.insertVersion(entity)
        pruneSnapshotsForDocument(uriString, maxSnapshots = 5)
        enforceGlobalSnapshotCap(maxFolderSizeBytes = 50 * 1024 * 1024L)
        entity
    }

    suspend fun saveSnapshotVersion(uriString: String, bytes: ByteArray, note: String? = null): DocumentVersionEntity = withContext(Dispatchers.IO) {
        val versionsDir = File(context.filesDir, "versions").apply { mkdirs() }
        val filename = "snapshot_${System.currentTimeMillis()}_${bytes.size}.bak"
        val snapshotFile = File(versionsDir, filename)
        
        FileOutputStream(snapshotFile).use { it.write(bytes) }

        val entity = DocumentVersionEntity(
            documentUri = uriString,
            snapshotFilePath = snapshotFile.absolutePath,
            versionNote = note,
            sizeBytes = bytes.size.toLong()
        )
        dao.insertVersion(entity)
        pruneSnapshotsForDocument(uriString, maxSnapshots = 5)
        enforceGlobalSnapshotCap(maxFolderSizeBytes = 50 * 1024 * 1024L)
        entity
    }

    suspend fun pruneSnapshotsForDocument(documentUri: String, maxSnapshots: Int = 5) = withContext(Dispatchers.IO) {
        try {
            val versions = dao.getVersionsListForDocument(documentUri)
            if (versions.size > maxSnapshots) {
                val toDelete = versions.drop(maxSnapshots)
                for (v in toDelete) {
                    dao.deleteVersionById(v.id)
                    val f = File(v.snapshotFilePath)
                    if (f.exists()) f.delete()
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun enforceGlobalSnapshotCap(maxFolderSizeBytes: Long = 50 * 1024 * 1024L) = withContext(Dispatchers.IO) {
        try {
            val versionsDir = File(context.filesDir, "versions")
            if (!versionsDir.exists()) return@withContext
            val allVersions = dao.getAllVersionsOldestFirst()
            var currentTotalSize = versionsDir.listFiles()?.sumOf { it.length() } ?: 0L
            for (v in allVersions) {
                if (currentTotalSize <= maxFolderSizeBytes) break
                dao.deleteVersionById(v.id)
                val f = File(v.snapshotFilePath)
                if (f.exists()) {
                    val len = f.length()
                    if (f.delete()) {
                        currentTotalSize -= len
                    }
                }
            }
            // Cleanup orphaned .bak files not present in active version entities
            val activeFiles = allVersions.map { it.snapshotFilePath }.toSet()
            versionsDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".bak") && file.absolutePath !in activeFiles) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun getVersions(uriString: String): Flow<List<DocumentVersionEntity>> {
        return dao.getVersionsForDocument(uriString)
    }

    fun resolveDocumentMetadata(uri: Uri): Document {
        var name = uri.lastPathSegment ?: "Untitled"
        var sizeBytes = 0L

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                            name = cursor.getString(nameIndex)
                        }
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            sizeBytes = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore cursor query exceptions for restrictive providers
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists()) {
                name = file.name
                sizeBytes = file.length()
            }
        }

        val mimeType = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }

        // Sniff real format by inspecting header bytes first
        val format = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                FileSniffer.sniffFormat(stream, name, mimeType)
            } ?: DocumentFormat.fromMimeType(mimeType, name)
        } catch (_: Throwable) {
            DocumentFormat.fromMimeType(mimeType, name)
        }

        return Document(
            uriString = uri.toString(),
            name = name,
            format = format,
            sizeBytes = sizeBytes,
            lastModified = System.currentTimeMillis(),
            lastAccessed = System.currentTimeMillis()
        )
    }
}
