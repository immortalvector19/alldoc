package com.alldoc.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.alldoc.app.core.engine.SaveResult
import com.alldoc.app.core.engine.scanner.DocumentScannerManager
import com.alldoc.app.core.model.Document
import com.alldoc.app.presentation.home.HomeScreen
import com.alldoc.app.presentation.home.HomeViewModel
import com.alldoc.app.presentation.scanner.ScanReviewScreen
import com.alldoc.app.presentation.scanner.ScanViewModel
import com.alldoc.app.presentation.theme.AllDocTheme
import com.alldoc.app.presentation.viewer.DocumentViewerScreen
import com.alldoc.app.presentation.viewer.DocumentViewerViewModel
import com.alldoc.app.presentation.viewer.ViewerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val viewerViewModel: DocumentViewerViewModel by viewModels()
    private val scanViewModel: ScanViewModel by viewModels()

    // Pending document URI queue consumed by NavHost upon readiness
    private val pendingDocumentUri = MutableStateFlow<Uri?>(null)

    // Storage Access Framework Picker (launches with */* so all document types are selectable)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            takePersistablePermission(uri)
            pendingDocumentUri.value = uri
        }
    }

    private var pendingCreateDocumentAction: ((Uri) -> Unit)? = null
    private var pendingCameraPhotoUri: Uri? = null

    // Save a Copy launcher (Storage Access Framework Create Document)
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri: Uri? ->
        if (destinationUri != null) {
            val customAction = pendingCreateDocumentAction
            if (customAction != null) {
                pendingCreateDocumentAction = null
                customAction(destinationUri)
            } else {
                viewerViewModel.saveCopyToUriWithResult(destinationUri) { result: SaveResult ->
                    val msg = when (result) {
                        is SaveResult.Success -> "Saved copy successfully"
                        is SaveResult.WriteFailedOriginalRestored -> "Save copy failed during write. Target restored."
                        is SaveResult.WriteFailedRestoreFailed -> "Save copy failed: ${result.message}"
                        is SaveResult.ValidationFailed -> "Save copy aborted: validation failed."
                        is SaveResult.PermissionDenied -> "Save copy aborted: ${result.message}"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            pendingCreateDocumentAction = null
        }
    }

    private val mergePdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { secondPdfUri: Uri? ->
        if (secondPdfUri != null) {
            viewerViewModel.mergePdfWithUri(secondPdfUri) { success ->
                Toast.makeText(this, if (success) "Merged PDF successfully" else "Failed to merge PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCaptureFallback()
        } else {
            Toast.makeText(this, "Camera permission required for scanner fallback", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCameraPhotoUri != null) {
            scanViewModel.setScannedPages(listOf(pendingCameraPhotoUri!!))
            navigateToScanReview = true
        }
    }

    private fun launchCameraCaptureFallback() {
        try {
            val cameraScansDir = File(cacheDir, "camera_scans").apply { mkdirs() }
            val photoFile = File(cameraScansDir, "scan_${System.currentTimeMillis()}.jpg")
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile
            )
            pendingCameraPhotoUri = photoUri
            cameraFallbackLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera fallback error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
                    // Request navigation to scan review
                    navigateToScanReview = true
                } else if (pdfUri != null) {
                    pendingDocumentUri.value = pdfUri
                }
            }
        }
    }

    private var navigateToScanReview = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only parse incoming intent on fresh cold start (not on process restoration/rotation)
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            AllDocTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    // Reactively navigate when a document is pending
                    val pendingUri by pendingDocumentUri.collectAsState()
                    LaunchedEffect(pendingUri) {
                        val uri = pendingUri ?: return@LaunchedEffect
                        viewerViewModel.loadDocument(uri)
                        pendingDocumentUri.value = null
                        navController.navigate("viewer") {
                            launchSingleTop = true
                        }
                    }

                    // Check if scan review is requested
                    LaunchedEffect(navigateToScanReview) {
                        if (navigateToScanReview) {
                            navigateToScanReview = false
                            navController.navigate("scan_review") {
                                launchSingleTop = true
                            }
                        }
                    }

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
                                    pendingDocumentUri.value = uri
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
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }

                        composable("viewer") {
                            val viewerState by viewerViewModel.uiState.collectAsState()
                            DocumentViewerScreen(
                                uiState = viewerState,
                                onBackClick = { navController.popBackStack() },
                                onSaveClick = {
                                    viewerViewModel.saveDocumentWithResult { result: SaveResult ->
                                        val msg = when (result) {
                                            is SaveResult.Success -> "Saved successfully"
                                            is SaveResult.WriteFailedOriginalRestored -> "Save failed during write. Original restored from backup."
                                            is SaveResult.WriteFailedRestoreFailed -> "CRITICAL: Save and restore failed. Backup preserved at ${result.backupFile.name}"
                                            is SaveResult.ValidationFailed -> "Save aborted: validation failed. Original left untouched."
                                            is SaveResult.PermissionDenied -> "Save aborted: ${result.message}"
                                        }
                                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                onShareClick = {
                                    val uri = viewerViewModel.activeUri
                                    if (uri != null) shareUri(uri)
                                },
                                onOpenInAnotherApp = { uri ->
                                    openInAnotherApp(uri)
                                },
                                onSaveCopyClick = {
                                    val current = viewerViewModel.uiState.value as? ViewerUiState.Success
                                    val fileName = current?.documentName ?: "document_copy"
                                    val safeFileName = if (fileName.contains('.')) fileName else "$fileName.${current?.format?.extension ?: "docx"}"
                                    createDocumentLauncher.launch(safeFileName)
                                },
                                onSplitPdfClick = { from, to ->
                                    val current = viewerViewModel.uiState.value as? ViewerUiState.Success
                                    val baseName = current?.documentName?.removeSuffix(".pdf") ?: "document"
                                    pendingCreateDocumentAction = { destUri ->
                                        viewerViewModel.splitPdfToUri(from, to, destUri) { result ->
                                            Toast.makeText(this@MainActivity, if (result is SaveResult.Success) "Pages $from-$to extracted successfully" else "Failed to extract pages", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    createDocumentLauncher.launch("${baseName}_p${from}_p${to}.pdf")
                                },
                                onMergePdfClick = {
                                    mergePdfPickerLauncher.launch(arrayOf("application/pdf", "*/*"))
                                },
                                onProtectPdfClick = { password ->
                                    val current = viewerViewModel.uiState.value as? ViewerUiState.Success
                                    val baseName = current?.documentName?.removeSuffix(".pdf") ?: "document"
                                    pendingCreateDocumentAction = { destUri ->
                                        viewerViewModel.protectPdfToUri(password, destUri) { result ->
                                            Toast.makeText(this@MainActivity, if (result is SaveResult.Success) "Protected with AES-256" else "Failed to protect PDF", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    createDocumentLauncher.launch("${baseName}_protected.pdf")
                                },
                                onLockDocumentClick = { password ->
                                    val current = viewerViewModel.uiState.value as? ViewerUiState.Success
                                    val baseName = current?.documentName ?: "document"
                                    val safeName = if (baseName.contains('.')) "${baseName.substringBeforeLast('.')}.vault" else "$baseName.vault"
                                    pendingCreateDocumentAction = { destUri ->
                                        viewerViewModel.lockDocumentToUri(password, destUri) { result ->
                                            Toast.makeText(this@MainActivity, if (result is SaveResult.Success) "Document encrypted to vault" else "Encryption failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    createDocumentLauncher.launch(safeName)
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
                                            pendingDocumentUri.value = compiledUri
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
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val data = intent.data

        if ((Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) && data != null) {
            val persistable = takePersistablePermission(data)
            val uriToUse = if (!persistable) cacheIncomingUri(data) else data
            pendingDocumentUri.value = uriToUse
        } else if (Intent.ACTION_SEND == action) {
            val streamUri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (streamUri != null) {
                val persistable = takePersistablePermission(streamUri)
                val uriToUse = if (!persistable) cacheIncomingUri(streamUri) else streamUri
                pendingDocumentUri.value = uriToUse
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val streamUris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            val firstUri = streamUris?.firstOrNull()
            if (firstUri != null) {
                val persistable = takePersistablePermission(firstUri)
                val uriToUse = if (!persistable) cacheIncomingUri(firstUri) else firstUri
                pendingDocumentUri.value = uriToUse
            }
        }
    }

    private fun cacheIncomingUri(uri: Uri): Uri {
        return try {
            val cachedDocsDir = File(filesDir, "cached_docs").apply { mkdirs() }
            val fileName = queryDisplayName(uri) ?: "shared_${System.currentTimeMillis()}"
            val targetFile = File(cachedDocsDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(targetFile)
        } catch (_: Exception) {
            uri // Fallback to raw URI if caching fails
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun takePersistablePermission(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startDocumentScan() {
        lifecycleScope.launch {
            try {
                val intentSender = DocumentScannerManager.getStartScanIntent(this@MainActivity)
                val request = IntentSenderRequest.Builder(intentSender).build()
                scannerLauncher.launch(request)
            } catch (_: Exception) {
                // Fallback to camera capture when Google Play Services Document Scanner is unavailable
                if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    launchCameraCaptureFallback()
                } else {
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
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

    private fun openInAnotherApp(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri) ?: "*/*"
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(viewIntent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this document: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
