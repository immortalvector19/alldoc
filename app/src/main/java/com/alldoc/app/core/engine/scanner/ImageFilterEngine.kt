package com.alldoc.app.core.engine.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.alldoc.app.core.model.Point2D

enum class ScanFilter {
    ORIGINAL,
    ENHANCED_COLOR,
    GRAYSCALE,
    BLACK_AND_WHITE
}

object ImageFilterEngine {

    fun applyFilter(bitmap: Bitmap, filter: ScanFilter): Bitmap {
        return when (filter) {
            ScanFilter.ORIGINAL -> bitmap
            ScanFilter.GRAYSCALE -> toGrayscale(bitmap)
            ScanFilter.BLACK_AND_WHITE -> toBlackAndWhite(bitmap)
            ScanFilter.ENHANCED_COLOR -> toEnhancedColor(bitmap)
        }
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun toEnhancedColor(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint()

        // Increase contrast and slight saturation boost
        val contrast = 1.3f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun toBlackAndWhite(src: Bitmap): Bitmap {
        val gray = toGrayscale(src)
        val width = gray.width
        val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)

        // Adaptive high-contrast thresholding for document ink
        val threshold = 145
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val lum = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

            pixels[i] = if (lum < threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun warpPerspective(
        src: Bitmap,
        topLeft: Point2D,
        topRight: Point2D,
        bottomRight: Point2D,
        bottomLeft: Point2D,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        val srcPoints = floatArrayOf(
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRight.x, bottomRight.y,
            bottomLeft.x, bottomLeft.y
        )
        val dstPoints = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val dest = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return dest
    }
}
