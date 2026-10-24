package com.alldoc.app.presentation.viewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alldoc.app.core.model.CellCoordinate
import com.alldoc.app.core.model.SpreadsheetDocumentModel

@Composable
fun SpreadsheetComponent(
    model: SpreadsheetDocumentModel,
    onUpdateCell: (row: Int, col: Int, rawValue: String, formula: String?) -> Unit,
    onSelectSheet: (sheetIndex: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeSheet = model.workbook.activeSheet

    var selectedCell by remember { mutableStateOf(CellCoordinate(0, 0)) }
    var inputFormula by remember(selectedCell) {
        val current = activeSheet.getCell(selectedCell.row, selectedCell.col)
        mutableStateOf(current.formula ?: current.rawValue)
    }

    val horizontalScrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        // Formula Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = selectedCell.address,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = inputFormula,
                        onValueChange = {
                            inputFormula = it
                            val isFormula = it.startsWith("=")
                            onUpdateCell(
                                selectedCell.row,
                                selectedCell.col,
                                if (isFormula) "" else it,
                                if (isFormula) it else null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        placeholder = { Text("Value or =FORMULA(A1:A5)") },
                        singleLine = true
                    )
                }

                // Quick Formula suggestions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Formulas: ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    listOf("=SUM(A1:A5)", "=AVERAGE(A1:A5)", "=COUNT(A1:A5)", "=MAX(A1:A5)").forEach { formula ->
                        AssistChip(
                            onClick = {
                                inputFormula = formula
                                onUpdateCell(selectedCell.row, selectedCell.col, "", formula)
                            },
                            label = { Text(formula, fontSize = 11.sp) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
            }
        }

        // Spreadsheet Grid (Matrix View)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Header Row (Column Labels A, B, C...)
                item {
                    Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                        // Empty top-left cell
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(32.dp)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("#", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        for (c in 0 until activeSheet.colCount) {
                            Box(
                                modifier = Modifier
                                    .width(85.dp)
                                    .height(32.dp)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = CellCoordinate.columnName(c),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Data Rows (1, 2, 3...)
                items(activeSheet.rowCount) { r ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Row Number Index
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(36.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${r + 1}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Cells in Row
                        for (c in 0 until activeSheet.colCount) {
                            val coord = CellCoordinate(r, c)
                            val cell = activeSheet.getCell(r, c)
                            val isSelected = selectedCell == coord

                            Box(
                                modifier = Modifier
                                    .width(85.dp)
                                    .height(36.dp)
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            r % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    )
                                    .border(
                                        if (isSelected) 2.dp else 0.5.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    )
                                    .clickable { selectedCell = coord }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = cell.displayValue,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = if (cell.displayValue.toDoubleOrNull() != null) TextAlign.End else TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sheet tabs row
        if (model.workbook.sheets.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = model.workbook.activeSheetIndex.coerceIn(0, model.workbook.sheets.size - 1),
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                model.workbook.sheets.forEachIndexed { index, sheet ->
                    Tab(
                        selected = index == model.workbook.activeSheetIndex,
                        onClick = { onSelectSheet(index) },
                        text = { Text(sheet.name, maxLines = 1) }
                    )
                }
            }
        }
    }
}
