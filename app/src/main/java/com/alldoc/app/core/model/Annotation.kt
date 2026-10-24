package com.alldoc.app.core.model

data class Point2D(val x: Float, val y: Float)

sealed class Annotation(val id: String = java.util.UUID.randomUUID().toString(), val pageIndex: Int) {
    data class InkStroke(
        val strokeId: String = java.util.UUID.randomUUID().toString(),
        val targetPage: Int,
        val points: List<Point2D>,
        val colorArgb: Long = 0xFF000000,
        val strokeWidth: Float = 4f
    ) : Annotation(strokeId, targetPage)

    data class Highlight(
        val highlightId: String = java.util.UUID.randomUUID().toString(),
        val targetPage: Int,
        val boundsLeft: Float,
        val boundsTop: Float,
        val boundsRight: Float,
        val boundsBottom: Float,
        val colorArgb: Long = 0x40FFEB3B // Semi-transparent yellow
    ) : Annotation(highlightId, targetPage)

    data class TextNote(
        val noteId: String = java.util.UUID.randomUUID().toString(),
        val targetPage: Int,
        val x: Float,
        val y: Float,
        val text: String,
        val fontSizeSp: Float = 14f,
        val colorArgb: Long = 0xFF1E1E1E,
        val backgroundArgb: Long? = 0xFFFFF9C4
    ) : Annotation(noteId, targetPage)

    data class SignatureStamp(
        val signatureId: String = java.util.UUID.randomUUID().toString(),
        val targetPage: Int,
        val paths: List<List<Point2D>>,
        val boundingX: Float,
        val boundingY: Float,
        val width: Float,
        val height: Float,
        val colorArgb: Long = 0xFF001F3F
    ) : Annotation(signatureId, targetPage)
}

data class DocumentAnnotations(
    val annotationsByPage: Map<Int, List<Annotation>> = emptyMap()
) {
    fun forPage(pageIndex: Int): List<Annotation> = annotationsByPage[pageIndex] ?: emptyList()

    fun add(annotation: Annotation): DocumentAnnotations {
        val current = annotationsByPage[annotation.pageIndex] ?: emptyList()
        val updated = annotationsByPage + (annotation.pageIndex to (current + annotation))
        return copy(annotationsByPage = updated)
    }

    fun remove(annotationId: String): DocumentAnnotations {
        val updated = annotationsByPage.mapValues { (_, list) ->
            list.filterNot { it.id == annotationId }
        }
        return copy(annotationsByPage = updated)
    }

    fun clearPage(pageIndex: Int): DocumentAnnotations {
        return copy(annotationsByPage = annotationsByPage - pageIndex)
    }
}
