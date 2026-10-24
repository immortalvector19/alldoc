package com.alldoc.app.core.data

import android.content.Context
import java.io.File

object CacheManager {

    private const val MAX_CACHE_SIZE_BYTES = 50L * 1024L * 1024L // 50MB safety limit
    private const val MAX_FILE_AGE_MILLIS = 24L * 60L * 60L * 1000L // 24 hours

    /**
     * Cleans up aged files and enforces the 50MB storage ceiling for cached external documents
     * and temporary PDF render files.
     */
    fun pruneCache(context: Context) {
        try {
            val cachedDocsDir = File(context.filesDir, "cached_docs")
            val tempDir = context.cacheDir
            cleanDirectory(cachedDocsDir)
            cleanTempPdfs(tempDir)
        } catch (_: Exception) {
            // Background cleanup failures must not crash the app
        }
    }

    private fun cleanDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        var totalSize = files.sumOf { it.length() }

        val sortedFiles = files.sortedBy { it.lastModified() }

        // 1. Evict files older than 24 hours
        for (file in sortedFiles) {
            if (now - file.lastModified() > MAX_FILE_AGE_MILLIS) {
                totalSize -= file.length()
                file.delete()
            }
        }

        // 2. If still exceeding 50MB, evict oldest files in LRU order
        for (file in sortedFiles) {
            if (totalSize <= MAX_CACHE_SIZE_BYTES) break
            if (file.exists()) {
                totalSize -= file.length()
                file.delete()
            }
        }
    }

    private fun cleanTempPdfs(cacheDir: File) {
        if (!cacheDir.exists() || !cacheDir.isDirectory) return
        val files = cacheDir.listFiles { _, name ->
            name.startsWith("pdf_") || name.endsWith(".pdf") || name.endsWith(".tmp")
        } ?: return
        val now = System.currentTimeMillis()
        for (file in files) {
            if (now - file.lastModified() > MAX_FILE_AGE_MILLIS) {
                file.delete()
            }
        }
    }
}
