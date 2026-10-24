package com.alldoc.app.presentation

import android.app.Application
import com.alldoc.app.presentation.home.HomeViewModel
import com.alldoc.app.presentation.scanner.ScanViewModel
import com.alldoc.app.presentation.viewer.DocumentViewerViewModel
import org.junit.Assert.assertNotNull
import org.junit.Test

class ViewModelFactoryTest {

    @Test
    fun testViewModelsHaveSingleArgumentApplicationConstructor() {
        // Verifies that AndroidViewModelFactory can reflectively discover the (Application) constructor
        val homeConstructor = HomeViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull("HomeViewModel must have a public (Application) constructor", homeConstructor)

        val viewerConstructor = DocumentViewerViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull("DocumentViewerViewModel must have a public (Application) constructor", viewerConstructor)

        val scanConstructor = ScanViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull("ScanViewModel must have a public (Application) constructor", scanConstructor)
    }
}
