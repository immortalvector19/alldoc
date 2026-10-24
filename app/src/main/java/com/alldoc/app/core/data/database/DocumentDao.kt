package com.alldoc.app.core.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents ORDER BY lastAccessed DESC")
    fun getAllRecent(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isFavorite = 1 ORDER BY lastAccessed DESC")
    fun getFavorites(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): DocumentEntity?

    @Query("""
        SELECT * FROM documents 
        WHERE name LIKE '%' || :query || '%' 
           OR searchContent LIKE '%' || :query || '%'
        ORDER BY lastAccessed DESC
    """)
    fun search(query: String): Flow<List<DocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(document: DocumentEntity)

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("UPDATE documents SET isFavorite = :isFavorite WHERE uriString = :uriString")
    suspend fun updateFavorite(uriString: String, isFavorite: Boolean)

    @Query("UPDATE documents SET lastAccessed = :timestamp WHERE uriString = :uriString")
    suspend fun updateLastAccessed(uriString: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET name = :newName, lastModified = :timestamp WHERE uriString = :uriString")
    suspend fun rename(uriString: String, newName: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM documents WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    // Version history
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: DocumentVersionEntity)

    @Query("SELECT * FROM document_versions WHERE documentUri = :documentUri ORDER BY timestamp DESC")
    fun getVersionsForDocument(documentUri: String): Flow<List<DocumentVersionEntity>>

    @Query("DELETE FROM document_versions WHERE documentUri = :documentUri")
    suspend fun deleteVersionsForDocument(documentUri: String)
}
