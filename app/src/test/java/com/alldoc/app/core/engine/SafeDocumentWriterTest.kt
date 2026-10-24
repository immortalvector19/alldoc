package com.alldoc.app.core.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SafeDocumentWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File

    @Before
    fun setup() {
        cacheDir = tempFolder.newFolder("cache")
    }

    class InMemoryStorageSink(
        var fileData: ByteArray,
        var canWrite: Boolean = true,
        var throwOnWriteBytesAfter: Int? = null,
        var maxFailures: Int = 1
    ) : DocumentStorageSink {
        override val location: String = "in-memory://test-document"
        private var failureCount = 0

        override fun openInputStream(): InputStream? {
            return ByteArrayInputStream(fileData)
        }

        override fun openOutputStream(mode: String): OutputStream? {
            if (!canWrite) return null

            return object : ByteArrayOutputStream() {
                private var bytesWritten = 0

                override fun write(b: Int) {
                    checkThrow(1)
                    super.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    checkThrow(len)
                    super.write(b, off, len)
                }

                private fun checkThrow(additional: Int) {
                    val limit = throwOnWriteBytesAfter
                    if (limit != null && failureCount < maxFailures && bytesWritten + additional > limit) {
                        failureCount++
                        val allowed = (limit - bytesWritten).coerceAtLeast(0)
                        if (allowed > 0) {
                            super.write(ByteArray(allowed) { 0xFF.toByte() }, 0, allowed)
                            bytesWritten += allowed
                        }
                        fileData = toByteArray()
                        throw IOException("Simulated disk full or I/O interruption mid-write!")
                    }
                    bytesWritten += additional
                }

                override fun close() {
                    super.close()
                    fileData = toByteArray()
                }
            }
        }
    }

    @Test
    fun testSuccessfulSaveAndPostWriteVerification() = runBlocking {
        val originalBytes = "Original Unmodified Document Content".toByteArray(Charsets.UTF_8)
        val updatedBytes = "Updated And Validated Document Content".toByteArray(Charsets.UTF_8)
        val sink = InMemoryStorageSink(originalBytes)

        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { _, stagedOut ->
                stagedOut.write(updatedBytes)
            },
            validateStagedFile = { stagedFile ->
                stagedFile.length() == updatedBytes.size.toLong()
            }
        )

        assertTrue(result is SaveResult.Success)
        assertEquals(String(updatedBytes), String(sink.fileData))
    }

    @Test
    fun testSimulatedWriteFailureMidWayRestoresOriginal() = runBlocking {
        val originalText = "CRITICAL_ORIGINAL_DATA_THAT_MUST_NOT_BE_LOST"
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)
        val updatedBytes = "NEW_DATA_THAT_WILL_FAIL_TO_COMPLETE_SAVING_DUE_TO_IO_ERROR".toByteArray(Charsets.UTF_8)

        // Simulate write throwing after 10 bytes on initial save attempt, but restore succeeds
        val sink = InMemoryStorageSink(
            fileData = originalBytes.clone(),
            throwOnWriteBytesAfter = 10,
            maxFailures = 1
        )

        var writeAttempted = false
        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { _, stagedOut ->
                writeAttempted = true
                stagedOut.write(updatedBytes)
            },
            validateStagedFile = { true }
        )

        assertTrue("Staging was written", writeAttempted)
        // Mid-way failure triggered restore!
        assertTrue(result is SaveResult.WriteFailedOriginalRestored)
        // Original content MUST be fully restored!
        assertEquals(originalText, String(sink.fileData))
    }

    @Test
    fun testSimulatedWriteFailureWhereRestoreFailsPreservesBackupOnDisk() = runBlocking {
        val originalText = "CRITICAL_ORIGINAL_DATA_THAT_MUST_NOT_BE_LOST"
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)
        val updatedBytes = "NEW_DATA_THAT_WILL_FAIL_TO_COMPLETE_SAVING_DUE_TO_IO_ERROR".toByteArray(Charsets.UTF_8)

        // Simulate continuous failure so restore also fails
        val sink = InMemoryStorageSink(
            fileData = originalBytes.clone(),
            throwOnWriteBytesAfter = 10,
            maxFailures = 10
        )

        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { _, stagedOut ->
                stagedOut.write(updatedBytes)
            },
            validateStagedFile = { true }
        )

        assertTrue(result is SaveResult.WriteFailedRestoreFailed)
        val failure = result as SaveResult.WriteFailedRestoreFailed
        assertTrue(failure.backupFile.exists())
        assertEquals(originalText, failure.backupFile.readText())
    }

    @Test
    fun testSimulatedReadOnlyUriLeavesOriginalUntouched() = runBlocking {
        val originalBytes = "Original Content Read Only".toByteArray(Charsets.UTF_8)
        val sink = InMemoryStorageSink(originalBytes, canWrite = false)

        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { _, stagedOut ->
                stagedOut.write("Should Never Write".toByteArray(Charsets.UTF_8))
            },
            validateStagedFile = { true }
        )

        assertTrue(result is SaveResult.PermissionDenied)
        assertEquals(String(originalBytes), String(sink.fileData))
    }

    @Test
    fun testValidationFailureLeavesOriginalUntouched() = runBlocking {
        val originalBytes = "Original Untouched Content".toByteArray(Charsets.UTF_8)
        val sink = InMemoryStorageSink(originalBytes)

        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { _, stagedOut ->
                stagedOut.write("Corrupt Staged Content".toByteArray(Charsets.UTF_8))
            },
            validateStagedFile = { false } // Re-parse validation fails
        )

        assertTrue(result is SaveResult.ValidationFailed)
        assertEquals(String(originalBytes), String(sink.fileData))
    }

    @Test
    fun testLargeFileStreamingDoesNotHoldEntireFileInMemory() = runBlocking {
        val tenMbSize = 10 * 1024 * 1024
        val largeFile = tempFolder.newFile("large_test.bin")
        largeFile.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024) { (it % 256).toByte() }
            for (i in 0 until (tenMbSize / chunk.size)) {
                out.write(chunk)
            }
        }

        val sink = object : DocumentStorageSink {
            override val location: String = largeFile.absolutePath
            override fun openInputStream(): InputStream = largeFile.inputStream()
            override fun openOutputStream(mode: String): OutputStream = largeFile.outputStream()
        }

        var bytesProcessed = 0L
        val result = SafeDocumentWriter.safeWrite(
            sink = sink,
            cacheDir = cacheDir,
            writeStagedFile = { backupFile, stagedOut ->
                backupFile.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        stagedOut.write(buffer, 0, read)
                        bytesProcessed += read
                    }
                }
            },
            validateStagedFile = { stagedFile ->
                stagedFile.length() == tenMbSize.toLong()
            }
        )

        assertTrue(result is SaveResult.Success)
        assertEquals(tenMbSize.toLong(), bytesProcessed)
        assertEquals(tenMbSize.toLong(), largeFile.length())
    }
}
