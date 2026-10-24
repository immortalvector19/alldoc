package com.alldoc.app.core.engine.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfRendererEngine(
    private val fileDescriptor: ParcelFileDescriptor
) : AutoCloseable {

    private val renderer: PdfRenderer = PdfRenderer(fileDescriptor)
    private val renderMutex = Mutex()

    val pageCount: Int
        get() = renderer.pageCount

    // LRU Cache: Max 32MB or 8 rendered pages in memory
    private val bitmapCache = object : LruCache<Int, Bitmap>(8) {
        override fun entryRemoved(evicted: Boolean, key: Int?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Note: Let GC reclaim bitmap memory safely
        }
    }

    suspend fun getPageDimensions(pageIndex: Int): Pair<Int, Int> = withContext(Dispatchers.Default) {
        renderMutex.withLock {
            if (pageIndex !in 0 until renderer.pageCount) return@withLock Pair(0, 0)
            renderer.openPage(pageIndex).use { page ->
                Pair(page.width, page.height)
            }
        }
    }

    suspend fun renderPage(pageIndex: Int, scaleFactor: Float = 2.0f): Bitmap? = withContext(Dispatchers.Default) {
        if (pageIndex !in 0 until renderer.pageCount) return@withContext null

        val cached = bitmapCache.get(pageIndex)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        renderMutex.withLock {
            try {
                renderer.openPage(pageIndex).use { page ->
                    val width = (page.width * scaleFactor).toInt().coerceAtLeast(1)
                    val height = (page.height * scaleFactor).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)

                    val matrix = Matrix().apply {
                        setScale(scaleFactor, scaleFactor)
                    }

                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmapCache.put(pageIndex, bitmap)
                    bitmap
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun clearCache() {
        bitmapCache.evictAll()
    }

    override fun close() {
        try {
            clearCache()
            renderer.close()
            fileDescriptor.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun fromFile(file: File): PdfRendererEngine {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return PdfRendererEngine(pfd)
        }
    }
}
