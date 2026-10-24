package com.alldoc.app.core.engine

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

sealed class SaveResult {
    object Success : SaveResult()
    data class ValidationFailed(val message: String) : SaveResult()
    data class PermissionDenied(val message: String) : SaveResult()
    data class WriteFailedOriginalRestored(val message: String) : SaveResult()
    data class WriteFailedRestoreFailed(val message: String, val backupFile: File) : SaveResult()
}

interface DocumentStorageSink {
    fun openInputStream(): InputStream?
    fun openOutputStream(mode: String = "wt"): OutputStream?
    val location: String
}

class ContentResolverStorageSink(
    private val contentResolver: ContentResolver,
    private val uri: Uri
) : DocumentStorageSink {
    override fun openInputStream(): InputStream? = contentResolver.openInputStream(uri)

    override fun openOutputStream(mode: String): OutputStream? {
        return contentResolver.openOutputStream(uri, mode)
            ?: if (mode != "wt") contentResolver.openOutputStream(uri, "wt") else null
    }

    override val location: String
        get() = uri.toString()
}

object SafeDocumentWriter {

    /**
     * Executes safe document write:
     * 1. Streams original content to a disk-based backup file (avoids holding entire file in RAM).
     * 2. Writes updated document to a staging file.
     * 3. Validates the staging file via re-parsing. If invalid, aborts with original left untouched.
     * 4. Attempts write to destination sink. If write or verification fails, restores original from backup.
     * 5. Post-write verification compares byte size and SHA-256 against staged file.
     */
    suspend fun safeWrite(
        sink: DocumentStorageSink,
        cacheDir: File,
        writeStagedFile: suspend (backupFile: File, stagedOut: OutputStream) -> Unit,
        validateStagedFile: suspend (stagedFile: File) -> Boolean,
        onSaveSnapshot: (suspend (backupFile: File) -> Unit)? = null
    ): SaveResult = withContext(Dispatchers.IO) {
        val backupFile = File.createTempFile("doc_backup_", ".tmp", cacheDir)
        val stagedFile = File.createTempFile("doc_staged_", ".tmp", cacheDir)

        try {
            // 1. Back up original by streaming directly to disk (never holding all bytes in RAM)
            val inputStream = sink.openInputStream()
                ?: return@withContext SaveResult.PermissionDenied("Cannot open input stream for ${sink.location}")

            inputStream.use { input ->
                FileOutputStream(backupFile).use { out ->
                    input.copyTo(out)
                }
            }

            // 2. Write updated document into staged file
            FileOutputStream(stagedFile).use { stagedOut ->
                writeStagedFile(backupFile, stagedOut)
            }

            // 3. Validate staged file before touching destination
            val isValid = try {
                validateStagedFile(stagedFile)
            } catch (e: Exception) {
                false
            }

            if (!isValid) {
                backupFile.delete()
                stagedFile.delete()
                return@withContext SaveResult.ValidationFailed("Validation failed during re-parse. Original file left untouched.")
            }

            // Optional: Persist pre-save snapshot
            try {
                onSaveSnapshot?.invoke(backupFile)
            } catch (_: Exception) {
                // Non-fatal if snapshot archiving fails
            }

            // 4. Compute staged file hash & length for verification
            val (stagedHash, stagedSize) = computeHashAndSize(stagedFile)

            // 5. Write to destination
            try {
                val outStream = sink.openOutputStream("rwt")
                    ?: sink.openOutputStream("wt")
                    ?: return@withContext SaveResult.PermissionDenied("Destination is read-only or permission was revoked. Original file left untouched.")

                outStream.use { out ->
                    FileInputStream(stagedFile).use { input ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                // Write threw exception, attempt restore
                return@withContext restoreOriginal(sink, backupFile, "Write interrupted: ${e.message}")
            }

            // 6. Post-write verification: Re-open destination and compare size & SHA-256
            val isVerified = try {
                sink.openInputStream()?.use { verifyIn ->
                    val (writtenHash, writtenSize) = computeHashAndSize(verifyIn)
                    writtenSize == stagedSize && writtenHash.equals(stagedHash, ignoreCase = true)
                } ?: false
            } catch (_: Exception) {
                false
            }

            if (!isVerified) {
                // Post-write verification failed! Restore original from backup
                return@withContext restoreOriginal(sink, backupFile, "Post-write verification failed (content/hash mismatch).")
            }

            // Everything succeeded cleanly! Clean up temp files
            backupFile.delete()
            stagedFile.delete()
            SaveResult.Success

        } catch (e: Exception) {
            // Unexpected error before write: original is untouched
            backupFile.delete()
            stagedFile.delete()
            SaveResult.ValidationFailed("Unexpected error: ${e.message}. Original file left untouched.")
        }
    }

    private fun restoreOriginal(
        sink: DocumentStorageSink,
        backupFile: File,
        failureReason: String
    ): SaveResult {
        return try {
            val restoreOut = sink.openOutputStream("wt")
                ?: return SaveResult.WriteFailedRestoreFailed(
                    "$failureReason Restore failed: output stream unavailable.",
                    backupFile
                )

            restoreOut.use { out ->
                FileInputStream(backupFile).use { input ->
                    input.copyTo(out)
                    out.flush()
                }
            }

            // Verify restore
            val restoredOk = sink.openInputStream()?.use { checkIn ->
                val (restoredHash, restoredSize) = computeHashAndSize(checkIn)
                val (expectedHash, expectedSize) = computeHashAndSize(backupFile)
                restoredSize == expectedSize && restoredHash.equals(expectedHash, ignoreCase = true)
            } ?: false

            if (restoredOk) {
                backupFile.delete()
                SaveResult.WriteFailedOriginalRestored("$failureReason Original file restored from backup.")
            } else {
                SaveResult.WriteFailedRestoreFailed(
                    "$failureReason Restore verification failed. Preserved backup at ${backupFile.absolutePath}",
                    backupFile
                )
            }
        } catch (restoreException: Exception) {
            SaveResult.WriteFailedRestoreFailed(
                "$failureReason Restore threw exception: ${restoreException.message}. Preserved backup at ${backupFile.absolutePath}",
                backupFile
            )
        }
    }

    fun computeHashAndSize(file: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
                totalBytes += read
            }
        }
        return Pair(digest.digest().joinToString("") { "%02x".format(it) }, totalBytes)
    }

    fun computeHashAndSize(inputStream: InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
            totalBytes += read
        }
        return Pair(digest.digest().joinToString("") { "%02x".format(it) }, totalBytes)
    }
}
