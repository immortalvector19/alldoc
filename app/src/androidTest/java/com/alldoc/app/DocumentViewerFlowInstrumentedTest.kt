package com.alldoc.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alldoc.app.core.engine.SaveResult
import com.alldoc.app.presentation.viewer.DocumentViewerViewModel
import com.alldoc.app.presentation.viewer.components.UnsavedChangesDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentViewerFlowInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testReadOnlyDocumentBlocksDirectSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = DocumentViewerViewModel(context as Application)

        val cachedFile = File(context.cacheDir, "cached_sample.txt").apply {
            writeText("Sample cached text")
        }
        val cachedUri = Uri.fromFile(cachedFile)
        viewModel.loadDocument(cachedUri)

        var saveResult: SaveResult? = null
        viewModel.saveDocumentWithResult { result ->
            saveResult = result
        }

        // When not loaded or read-only/cached, direct save must be safely rejected
        assertTrue(saveResult is SaveResult.ValidationFailed || saveResult is SaveResult.PermissionDenied)
    }

    @Test
    fun testUnsavedChangesPromptCanBeRendered() {
        var discarded = false
        composeTestRule.setContent {
            UnsavedChangesDialog(
                onDismiss = {},
                onDiscard = { discarded = true },
                onSave = {}
            )
        }

        composeTestRule.onNodeWithText("Unsaved Changes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discard").assertIsDisplayed().performClick()
        assertTrue("Discard callback must be triggered", discarded)
    }
}
