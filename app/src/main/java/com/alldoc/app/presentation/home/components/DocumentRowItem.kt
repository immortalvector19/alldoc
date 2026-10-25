package com.alldoc.app.presentation.home.components

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alldoc.app.core.model.Document
import com.alldoc.app.core.model.DocumentFormat
import java.util.Date

@Composable
fun DocumentRowItem(
    document: Document,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Minimal neutral format label (e.g. "PDF", "DOCX")
            Box(
                modifier = Modifier.width(42.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = getNeutralFormatTag(document.format),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Title & Meta (date + size)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dateStr = DateFormat.format("MMM\u00A0d, yyyy", Date(document.lastModified)).toString()
                    Text(
                        text = "$dateStr \u00B7 ${document.formattedSize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Star favorite toggle (48x48dp target)
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (document.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (document.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (document.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // More options menu (48x48dp target)
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Document options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Share", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Save to… (Export)", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuExpanded = false
                            onSaveCopy()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }

        // 1dp Hairline divider
        HorizontalDivider(
            modifier = Modifier.padding(start = 58.dp, end = 16.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

private fun getNeutralFormatTag(format: DocumentFormat): String {
    return when (format) {
        DocumentFormat.PDF -> "PDF"
        DocumentFormat.DOCX -> "DOCX"
        DocumentFormat.DOC -> "DOC"
        DocumentFormat.XLSX -> "XLSX"
        DocumentFormat.XLS -> "XLS"
        DocumentFormat.CSV -> "CSV"
        DocumentFormat.PPTX -> "PPTX"
        DocumentFormat.PPT -> "PPT"
        DocumentFormat.TXT -> "TXT"
        DocumentFormat.RTF -> "RTF"
        DocumentFormat.MARKDOWN -> "MD"
        DocumentFormat.IMAGE_JPEG -> "JPG"
        DocumentFormat.IMAGE_PNG -> "PNG"
        DocumentFormat.IMAGE_WEBP -> "WEBP"
        DocumentFormat.VAULT -> "VAULT"
        DocumentFormat.UNKNOWN -> "FILE"
    }
}
