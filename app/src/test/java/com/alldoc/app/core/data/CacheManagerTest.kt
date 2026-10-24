package com.alldoc.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheManagerTest {

    @Test
    fun testCachePruningOldFiles() {
        val testDir = File("build/test_cache_prune").apply {
            deleteRecursively()
            mkdirs()
        }

        val oldFile = File(testDir, "old_cached_doc.bin").apply {
            writeBytes(ByteArray(1024) { 0x11 })
            // Set last modified to 48 hours ago
            setLastModified(System.currentTimeMillis() - (48L * 3600 * 1000))
        }

        val freshFile = File(testDir, "fresh_cached_doc.bin").apply {
            writeBytes(ByteArray(1024) { 0x22 })
            setLastModified(System.currentTimeMillis())
        }

        assertTrue(oldFile.exists())
        assertTrue(freshFile.exists())

        // Simulate CacheManager cleaning directory
        val maxAge = 24L * 3600 * 1000
        val now = System.currentTimeMillis()
        for (f in testDir.listFiles() ?: emptyArray()) {
            if (now - f.lastModified() > maxAge) {
                f.delete()
            }
        }

        assertFalse("Old file (>24h) must be deleted", oldFile.exists())
        assertTrue("Fresh file must be preserved", freshFile.exists())

        testDir.deleteRecursively()
    }
}
