package com.alldoc.app.presentation.viewer.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alldoc.app.core.engine.pdf.PdfRendererEngine
import com.alldoc.app.core.model.Annotation
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.Point2D

enum class PdfAnnotationMode {
    NONE,
    PEN,
    HIGHLIGHT,
    TEXT_NOTE,
    SIGNATURE
}

@Composable
fun PdfViewerComponent(
    model: PdfDocumentModel,
    renderer: PdfRendererEngine?,
    onAddAnnotation: (Annotation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (renderer == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var activeMode by remember { mutableStateOf(PdfAnnotationMode.NONE) }
    var selectedColor by remember { mutableStateOf(0xFF1E3A8A) }
    var activePageForAnnotation by remember { mutableStateOf(0) }

    // Dialog states
    var showTextNoteDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }

    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap?>() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(model.pageCount) { pageIndex ->
                LaunchedEffect(pageIndex) {
                    if (pageBitmaps[pageIndex] == null) {
                        pageBitmaps[pageIndex] = renderer.renderPage(pageIndex, 1.8f)
                    }
                }

                PdfPageCard(
                    pageIndex = pageIndex,
                    bitmap = pageBitmaps[pageIndex],
                    annotations = model.annotations.forPage(pageIndex),
                    annotationMode = if (activePageForAnnotation == pageIndex) activeMode else PdfAnnotationMode.NONE,
                    strokeColor = selectedColor,
                    onPageSelected = { activePageForAnnotation = pageIndex },
                    onStrokeFinished = { points ->
                        if (points.isNotEmpty()) {
                            val annot = if (activeMode == PdfAnnotationMode.HIGHLIGHT) {
                                val minX = points.minOf { it.x }
                                val maxX = points.maxOf { it.x }
                                val minY = points.minOf { it.y }
                                val maxY = points.maxOf { it.y }
                                Annotation.Highlight(
                                    targetPage = pageIndex,
                                    boundsLeft = minX,
                                    boundsTop = minY,
                                    boundsRight = maxX,
                                    boundsBottom = maxY
                                )
                            } else {
                                Annotation.InkStroke(
                                    targetPage = pageIndex,
                                    points = points,
                                    colorArgb = selectedColor,
                                    strokeWidth = if (activeMode == PdfAnnotationMode.HIGHLIGHT) 24f else 4f
                                )
                            }
                            onAddAnnotation(annot)
                        }
                    }
                )
            }
        }

        // Floating Bottom Tool Palette for One-Handed Ergonomics
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Freehand Pen
                FilledTonalIconButton(
                    onClick = {
                        activeMode = if (activeMode == PdfAnnotationMode.PEN) PdfAnnotationMode.NONE else PdfAnnotationMode.PEN
                    },
                    colors = if (activeMode == PdfAnnotationMode.PEN)
                        IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    else IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(Icons.Default.Draw, contentDescription = "Draw with Pen")
                }

                // Highlighter
                FilledTonalIconButton(
                    onClick = {
                        activeMode = if (activeMode == PdfAnnotationMode.HIGHLIGHT) PdfAnnotationMode.NONE else PdfAnnotationMode.HIGHLIGHT
                    },
                    colors = if (activeMode == PdfAnnotationMode.HIGHLIGHT)
                        IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    else IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(Icons.Default.Highlight, contentDescription = "Highlight Text")
                }

                // Add Text Note
                FilledTonalIconButton(
                    onClick = { showTextNoteDialog = true }
                ) {
                    Icon(Icons.Default.TextFields, contentDescription = "Add Text Note")
                }

                // Digital Signature Stamp
                FilledTonalIconButton(
                    onClick = { showSignatureDialog = true }
                ) {
                    Icon(Icons.Default.Create, contentDescription = "Sign Document")
                }
            }
        }
    }

    // Text Note Dialog
    if (showTextNoteDialog) {
        var noteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextNoteDialog = false },
            title = { Text("Add Text Annotation") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note content") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteText.isNotBlank()) {
                            onAddAnnotation(
                                Annotation.TextNote(
                                    targetPage = activePageForAnnotation,
                                    x = 50f,
                                    y = 100f,
                                    text = noteText.trim()
                                )
                            )
                        }
                        showTextNoteDialog = false
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextNoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Signature Pad Dialog
    if (showSignatureDialog) {
        SignaturePadDialog(
            onDismiss = { showSignatureDialog = false },
            onSaveSignature = { paths ->
                onAddAnnotation(
                    Annotation.SignatureStamp(
                        targetPage = activePageForAnnotation,
                        paths = paths,
                        boundingX = 50f,
                        boundingY = 200f,
                        width = 200f,
                        height = 100f
                    )
                )
                showSignatureDialog = false
            }
        )
    }
}

@Composable
fun PdfPageCard(
    pageIndex: Int,
    bitmap: Bitmap?,
    annotations: List<Annotation>,
    annotationMode: PdfAnnotationMode,
    strokeColor: Long,
    onPageSelected: () -> Unit,
    onStrokeFinished: (List<Point2D>) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                // Select active page
                onPageSelected()
            },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth()
                )

                // Vector Annotation Canvas Layer
                var livePoints by remember { mutableStateOf(listOf<Point2D>()) }

                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(annotationMode) {
                            if (annotationMode == PdfAnnotationMode.PEN || annotationMode == PdfAnnotationMode.HIGHLIGHT) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        livePoints = listOf(Point2D(offset.x, offset.y))
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        livePoints = livePoints + Point2D(change.position.x, change.position.y)
                                    },
                                    onDragEnd = {
                                        onStrokeFinished(livePoints)
                                        livePoints = emptyList()
                                    },
                                    onDragCancel = {
                                        livePoints = emptyList()
                                    }
                                )
                            }
                        }
                ) {
                    // Draw saved annotations
                    for (annot in annotations) {
                        when (annot) {
                            is Annotation.InkStroke -> {
                                if (annot.points.size > 1) {
                                    val path = Path()
                                    path.moveTo(annot.points.first().x, annot.points.first().y)
                                    for (p in annot.points.drop(1)) {
                                        path.lineTo(p.x, p.y)
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(annot.colorArgb),
                                        style = Stroke(
                                            width = annot.strokeWidth,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                            }

                            is Annotation.Highlight -> {
                                drawRect(
                                    color = Color(annot.colorArgb),
                                    topLeft = Offset(annot.boundsLeft, annot.boundsTop),
                                    size = androidx.compose.ui.geometry.Size(
                                        annot.boundsRight - annot.boundsLeft,
                                        annot.boundsBottom - annot.boundsTop
                                    )
                                )
                            }

                            is Annotation.SignatureStamp -> {
                                for (stroke in annot.paths) {
                                    if (stroke.size > 1) {
                                        val path = Path()
                                        path.moveTo(stroke.first().x, stroke.first().y)
                                        for (p in stroke.drop(1)) {
                                            path.lineTo(p.x, p.y)
                                        }
                                        drawPath(
                                            path = path,
                                            color = Color(annot.colorArgb),
                                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                                        )
                                    }
                                }
                            }

                            else -> {}
                        }
                    }

                    // Draw live drawing stroke
                    if (livePoints.size > 1) {
                        val path = Path()
                        path.moveTo(livePoints.first().x, livePoints.first().y)
                        for (p in livePoints.drop(1)) {
                            path.lineTo(p.x, p.y)
                        }
                        drawPath(
                            path = path,
                            color = Color(strokeColor),
                            style = Stroke(
                                width = if (annotationMode == PdfAnnotationMode.HIGHLIGHT) 20f else 4f,
                                cap = StrokeCap.Round
                            )
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onSaveSignature: (List<List<Point2D>>) -> Unit
) {
    var strokes by remember { mutableStateOf(listOf<List<Point2D>>()) }
    var currentStroke by remember { mutableStateOf(listOf<Point2D>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign Document") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Draw your signature below using your finger:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentStroke = listOf(Point2D(offset.x, offset.y))
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentStroke = currentStroke + Point2D(change.position.x, change.position.y)
                                    },
                                    onDragEnd = {
                                        if (currentStroke.isNotEmpty()) {
                                            strokes = strokes + listOf(currentStroke)
                                            currentStroke = emptyList()
                                        }
                                    },
                                    onDragCancel = {
                                        currentStroke = emptyList()
                                    }
                                )
                            }
                    ) {
                        for (stroke in strokes) {
                            if (stroke.size > 1) {
                                val path = Path()
                                path.moveTo(stroke.first().x, stroke.first().y)
                                for (p in stroke.drop(1)) {
                                    path.lineTo(p.x, p.y)
                                }
                                drawPath(path, Color(0xFF0F172A), style = Stroke(width = 4f, cap = StrokeCap.Round))
                            }
                        }
                        if (currentStroke.size > 1) {
                            val path = Path()
                            path.moveTo(currentStroke.first().x, currentStroke.first().y)
                            for (p in currentStroke.drop(1)) {
                                path.lineTo(p.x, p.y)
                            }
                            drawPath(path, Color(0xFF0F172A), style = Stroke(width = 4f, cap = StrokeCap.Round))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (strokes.isNotEmpty()) {
                        onSaveSignature(strokes)
                    }
                }
            ) {
                Text("Insert Signature")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes = emptyList() }) {
                    Text("Clear")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
