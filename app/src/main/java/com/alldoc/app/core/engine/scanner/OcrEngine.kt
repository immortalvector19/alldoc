package com.alldoc.app.core.engine.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrTextBlock>
)

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect?,
    val lines: List<String>
)

object OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeText(context: Context, imageUri: Uri): OcrResult = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            processImage(image)
        } catch (e: Exception) {
            OcrResult(fullText = "", blocks = emptyList())
        }
    }

    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            processImage(image)
        } catch (e: Exception) {
            OcrResult(fullText = "", blocks = emptyList())
        }
    }

    private suspend fun processImage(image: InputImage): OcrResult {
        val visionText = recognizer.process(image).await()
        val blocks = visionText.textBlocks.map { block: com.google.mlkit.vision.text.Text.TextBlock ->
            OcrTextBlock(
                text = block.text,
                boundingBox = block.boundingBox,
                lines = block.lines.map { line: com.google.mlkit.vision.text.Text.Line -> line.text }
            )
        }
        return OcrResult(
            fullText = visionText.text,
            blocks = blocks
        )
    }
}
