package com.alldoc.app.presentation.viewer.coordinator

import com.alldoc.app.core.engine.openxml.XlsxEngine
import com.alldoc.app.core.model.Annotation
import com.alldoc.app.core.model.CellData
import com.alldoc.app.core.model.PdfDocumentModel
import com.alldoc.app.core.model.SpreadsheetDocumentModel
import com.alldoc.app.core.model.TextDocumentModel
import com.alldoc.app.core.model.WordDocumentModel
import com.alldoc.app.core.model.WordTextRun

object DocumentEditCoordinator {

    fun addPdfAnnotation(pdfModel: PdfDocumentModel, annotation: Annotation): PdfDocumentModel {
        val updatedAnnotations = pdfModel.annotations.add(annotation)
        return pdfModel.copy(annotations = updatedAnnotations)
    }

    fun updateWordText(wordModel: WordDocumentModel, paragraphIndex: Int, newText: String): WordDocumentModel {
        val updatedParagraphs = wordModel.paragraphs.toMutableList()
        if (paragraphIndex in updatedParagraphs.indices) {
            val oldP = updatedParagraphs[paragraphIndex]
            val updatedRun = oldP.runs.firstOrNull()?.copy(text = newText) ?: WordTextRun(text = newText)
            updatedParagraphs[paragraphIndex] = oldP.copy(runs = listOf(updatedRun))
        }
        return wordModel.copy(paragraphs = updatedParagraphs)
    }

    fun updateExcelCell(
        xlsxModel: SpreadsheetDocumentModel,
        row: Int,
        col: Int,
        rawValue: String,
        formula: String? = null
    ): SpreadsheetDocumentModel {
        val updatedSheet = xlsxModel.workbook.activeSheet.updateCell(
            row, col, CellData(rawValue = rawValue, formula = formula)
        )
        val evaluatedSheet = XlsxEngine.evaluateFormulas(updatedSheet)
        val updatedWorkbook = xlsxModel.workbook.updateActiveSheet(evaluatedSheet)
        return xlsxModel.copy(workbook = updatedWorkbook)
    }

    fun selectSpreadsheetSheet(
        xlsxModel: SpreadsheetDocumentModel,
        sheetIndex: Int
    ): SpreadsheetDocumentModel {
        if (sheetIndex in xlsxModel.workbook.sheets.indices) {
            val updatedWorkbook = xlsxModel.workbook.copy(activeSheetIndex = sheetIndex)
            return xlsxModel.copy(workbook = updatedWorkbook)
        }
        return xlsxModel
    }

    fun updateText(textModel: TextDocumentModel, newText: String): TextDocumentModel {
        return textModel.copy(text = newText)
    }
}
