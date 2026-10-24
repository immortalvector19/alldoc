package com.alldoc.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alldoc.app.core.engine.scanner.DocumentScannerManager
import com.alldoc.app.core.model.Document
import com.alldoc.app.presentation.home.HomeScreen
import com.alldoc.app.presentation.home.HomeViewModel
import com.alldoc.app.presentation.scanner.ScanReviewScreen
import com.alldoc.app.presentation.scanner.ScanViewModel
import com.alldoc.app.presentation.theme.AllDocTheme
import com.alldoc.app.presentation.viewer.DocumentViewerScreen
import com.alldoc.app.presentation.viewer.DocumentViewerViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val viewerViewModel: DocumentViewerViewModel by viewModels()
    private val scanViewModel: ScanViewModel by viewModels()

    // File picker launcher (Storage Access Framework)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            takePersistablePermission(uri)
            openDocumentInViewer(uri)
        }
    }

    // ML Kit Document Scanner Intent launcher
    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = DocumentScannerManager.parseResult(result.data)
            if (scanResult != null) {
                val pdfUri = scanResult.pdf?.uri
                val pages = scanResult.pages

                if (!pages.isNullOrEmpty()) {
                    val pageUris = pages.map { it.imageUri }
                    scanViewModel.setScannedPages(pageUris)
                    navControllerHolder?.navigate("scan_review")
                } else if (pdfUri != null) {
                    openDocumentInViewer(pdfUri)
                }
            }
        }
    }

    private var navControllerHolder: androidx.navigation.NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            AllDocTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    navControllerHolder = navController

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            val uiState by homeViewModel.uiState.collectAsState()
                            HomeScreen(
                                uiState = uiState,
                                onCategorySelected = { homeViewModel.selectCategory(it) },
                                onTabSelected = { homeViewModel.selectTab(it) },
                                onSearchQueryChanged = { homeViewModel.updateSearchQuery(it) },
                                onDocumentClick = { doc ->
                                    val uri = Uri.parse(doc.uriString)
                                    openDocumentInViewer(uri)
                                },
                                onToggleFavorite = { uriStr, isFav ->
                                    homeViewModel.toggleFavorite(uriStr, isFav)
                                },
                                onDeleteDocument = { homeViewModel.deleteDocument(it) },
                                onRenameDocument = { uriStr, name ->
                                    homeViewModel.renameDocument(uriStr, name)
                                },
                                onShareDocument = { shareDocument(it) },
                                onScanClick = { startDocumentScan() },
                                onOpenFilePicker = {
                                    filePickerLauncher.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "application/msword",
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "application/vnd.ms-excel",
                                            "text/csv",
                                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                            "application/vnd.ms-powerpoint",
                                            "text/plain",
                                            "text/markdown",
                                            "application/rtf",
                                            "image/*"
                                        )
                                    )
                                }
                            )
                        }

                        composable(
                            route = "viewer/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("encodedUri") ?: ""
                            val uriString = URLDecoder.decode(encodedUri, StandardCharsets.UTF_8.name())
                            val uri = Uri.parse(uriString)

                            LaunchedEffect(uriString) {
                                viewerViewModel.loadDocument(uri)
                            }

                            val viewerState by viewerViewModel.uiState.collectAsState()
                            DocumentViewerScreen(
                                uiState = viewerState,
                                onBackClick = { navController.popBackStack() },
                                onSaveClick = {
                                    viewerViewModel.saveDocument { success ->
                                        if (success) {
                                            Toast.makeText(this@MainActivity, "Saved successfully", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onShareClick = {
                                    shareUri(uri)
                                },
                                viewModel = viewerViewModel
                            )
                        }

                        composable("scan_review") {
                            val scanState by scanViewModel.uiState.collectAsState()
                            ScanReviewScreen(
                                uiState = scanState,
                                onBackClick = { navController.popBackStack() },
                                onFilterSelected = { pageIdx, filter ->
                                    scanViewModel.applyFilterToPage(pageIdx, filter)
                                },
                                onPageSelected = { scanViewModel.selectPage(it) },
                                onSavePdf = { title ->
                                    scanViewModel.compileToPdf(title) { compiledUri ->
                                        if (compiledUri != null) {
                                            navController.popBackStack()
                                            openDocumentInViewer(compiledUri)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Failed to compile PDF", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val data = intent.data

        if ((Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) && data != null) {
            takePersistablePermission(data)
            openDocumentInViewer(data)
        } else if (Intent.ACTION_SEND == action) {
            val streamUri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (streamUri != null) {
                takePersistablePermission(streamUri)
                openDocumentInViewer(streamUri)
            }
        }
    }

    private fun takePersistablePermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some providers don't support persistable permissions
        }
    }

    private fun openDocumentInViewer(uri: Uri) {
        val encodedUri = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.name())
        navControllerHolder?.navigate("viewer/$encodedUri") {
            launchSingleTop = true
        }
    }

    private fun startDocumentScan() {
        lifecycleScope.launch {
            try {
                val intentSender = DocumentScannerManager.getStartScanIntent(this@MainActivity)
                val request = IntentSenderRequest.Builder(intentSender).build()
                scannerLauncher.launch(request)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Scanner: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareDocument(doc: Document) {
        val uri = Uri.parse(doc.uriString)
        shareUri(uri)
    }

    private fun shareUri(uri: Uri) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Document via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share document: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
