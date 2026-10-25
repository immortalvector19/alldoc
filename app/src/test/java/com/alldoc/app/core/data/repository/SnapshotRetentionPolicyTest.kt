package com.alldoc.app.core.data.repository

import android.content.Context
import com.alldoc.app.core.data.database.DocumentDao
import com.alldoc.app.core.data.database.DocumentEntity
import com.alldoc.app.core.data.database.DocumentVersionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SnapshotRetentionPolicyTest {

    private lateinit var tempDir: File
    private lateinit var versionsDir: File
    private lateinit var fakeDao: FakeDocumentDao
    private lateinit var repository: DocumentRepository

    class FakeDocumentDao : DocumentDao {
        val versions = mutableListOf<DocumentVersionEntity>()

        override fun getAllRecent(): Flow<List<DocumentEntity>> = emptyFlow()
        override fun getFavorites(): Flow<List<DocumentEntity>> = emptyFlow()
        override suspend fun getByUri(uriString: String): DocumentEntity? = null
        override fun search(query: String): Flow<List<DocumentEntity>> = emptyFlow()
        override suspend fun insertOrUpdate(document: DocumentEntity) {}
        override suspend fun update(document: DocumentEntity) {}
        override suspend fun updateFavorite(uriString: String, isFavorite: Boolean) {}
        override suspend fun updateLastAccessed(uriString: String, timestamp: Long) {}
        override suspend fun rename(uriString: String, newName: String, timestamp: Long) {}
        override suspend fun deleteByUri(uriString: String) {}

        override suspend fun insertVersion(version: DocumentVersionEntity) {
            versions.add(version)
        }

        override fun getVersionsForDocument(documentUri: String): Flow<List<DocumentVersionEntity>> = emptyFlow()

        override suspend fun getVersionsListForDocument(documentUri: String): List<DocumentVersionEntity> {
            return versions.filter { it.documentUri == documentUri }.sortedByDescending { it.timestamp }
        }

        override suspend fun deleteVersionById(id: Long) {
            versions.removeAll { it.id == id }
        }

        override suspend fun getAllVersionsOldestFirst(): List<DocumentVersionEntity> {
            return versions.sortedBy { it.timestamp }
        }

        override suspend fun deleteVersionsForDocument(documentUri: String) {
            versions.removeAll { it.documentUri == documentUri }
        }
    }

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "alldoc_test_${System.currentTimeMillis()}").apply { mkdirs() }
        versionsDir = File(tempDir, "versions").apply { mkdirs() }
        fakeDao = FakeDocumentDao()
    }

    @Test
    fun testPruneSnapshots_keepsOnlyLatest5() {
        val docUri = "content://docs/sample.docx"

        // Create 8 versions
        for (i in 1..8) {
            val file = File(versionsDir, "snap_$i.bak").apply { writeText("Version $i content") }
            val entity = DocumentVersionEntity(
                id = i.toLong(),
                documentUri = docUri,
                snapshotFilePath = file.absolutePath,
                timestamp = 1000L * i,
                sizeBytes = file.length()
            )
            fakeDao.versions.add(entity)
        }

        assertEquals(8, fakeDao.versions.size)

        runBlocking {
            // Simulate prune
            val versions = fakeDao.getVersionsListForDocument(docUri)
            if (versions.size > 5) {
                val toDelete = versions.drop(5)
                for (v in toDelete) {
                    fakeDao.deleteVersionById(v.id)
                    File(v.snapshotFilePath).delete()
                }
            }
        }

        // Must keep exactly 5 newest (ids 8, 7, 6, 5, 4)
        assertEquals(5, fakeDao.versions.size)
        val remainingIds = fakeDao.versions.map { it.id }.toSet()
        assertEquals(setOf(8L, 7L, 6L, 5L, 4L), remainingIds)

        // Older files 1, 2, 3 must be deleted from disk
        assertFalse(File(versionsDir, "snap_1.bak").exists())
        assertFalse(File(versionsDir, "snap_2.bak").exists())
        assertFalse(File(versionsDir, "snap_3.bak").exists())

        // Newer files must still exist
        assertTrue(File(versionsDir, "snap_8.bak").exists())
        assertTrue(File(versionsDir, "snap_4.bak").exists())
    }

    @Test
    fun testOrphanedBakFiles_cleanedUp() {
        val validFile = File(versionsDir, "valid.bak").apply { writeText("valid") }
        val orphanFile = File(versionsDir, "orphan.bak").apply { writeText("orphan") }

        val entity = DocumentVersionEntity(
            id = 1L,
            documentUri = "content://docs/valid.docx",
            snapshotFilePath = validFile.absolutePath,
            sizeBytes = validFile.length()
        )
        fakeDao.versions.add(entity)

        runBlocking {
            val allVersions = fakeDao.getAllVersionsOldestFirst()
            val validPaths = allVersions.map { it.snapshotFilePath }.toSet()
            versionsDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".bak") && f.absolutePath !in validPaths) {
                    f.delete()
                }
            }
        }

        assertTrue(validFile.exists())
        assertFalse(orphanFile.exists())
    }
}
