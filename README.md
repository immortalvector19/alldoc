# AllDoc — Universal Mobile Document Suite for Android

<p align="center">
  <strong>One native Android app to open, view, edit, scan, and secure every document.</strong><br>
  Targeting Android 8.0 (API 26) through Android 15 (API 35). 100% Native Kotlin, Jetpack Compose, Material 3, and strictly Offline-First.
</p>

---

## 🏛️ Executive Summary & Engineering Philosophy

**AllDoc** is an offline-first Android document suite engineered to eliminate bloat, privacy compromises, and data-loss risks common in mobile document apps:
1. **Lightweight & High-Performance**: No desktop dependencies (like Apache POI) or 150MB+ bundle bloat. Release APK is **~39.9 MB** with ABI-tailored native libraries and R8 full-mode optimization.
2. **100% Offline-First & Private**: Zero network permissions (`android.permission.INTERNET` is not requested). Documents and scans never leave the device.
3. **Safe, Verified Document Writing**: Edits to documents use a strict two-stage write protocol: original content is backed up to a temporary cache file, modifications are staged, and writes to content URIs are verified via size and SHA-256 hash comparison before completion. If verification or write fails, the original is restored.
4. **Non-Destructive XML Patching**: Word (`.docx`) edits patch only changed `<w:t>` text runs in the underlying XML DOM, preserving styles, tables, headers, footers, and images. Excel (`.xlsx`) edits patch only modified cells, preserving formulas, merged cells, number formatting, and sheet structures while setting `fullCalcOnLoad="1"`.
5. **Cold-Start Intent Resiliency**: Document intents from WhatsApp, Gmail, Chrome, and system file managers are enqueued reactively in state and executed once the Compose navigation host is mounted, eliminating dropped intents on cold starts.
6. **Refined Apple-Design Aesthetics**: Custom Material 3 theme featuring a signature indigo-violet brand (`#5B5BF0`), mint accents (`#10B981`), warm neutral surfaces, soft true-dark mode (`#0E1116`), bottom navigation, squircle format badges, fluid spring press animations, and distraction-free reading mode. Dynamic Color is opt-in (disabled by default) so the brand identity shines.

---

## 📱 Format Support & Honest Feature Capabilities

| Document Category | Formats | Real-World Capabilities | In-App Edit & Mutation Fidelity |
|---|---|---|---|
| **PDF** | `.pdf` | Hardware-accelerated page rendering with memory-budgeted `LruCache` (capped at 64MB) and bitmap recycling. View password-protected PDFs. | Add ink drawings, semi-transparent multiply-blended highlights, and TrueType Unicode text notes (supports accented Latin, Chinese, Arabic). Split page ranges, merge documents, and AES-256 password-protect. Annotations flattened vectorially via PdfBox-Android. |
| **Word (Modern)** | `.docx` | Paragraphs, headings, bullet lists, nested tables, images. | In-place run-level DOM text patching. Preserves unedited runs, bold/italic formatting, hyperlinks, headers/footers, and document relationships. |
| **Word (Legacy)** | `.doc` | Detected via `D0CF11E0` OLE2 magic bytes. Extracted text preview. | **Read-Only**: In-place saving is disabled to prevent corruption. Users can "Open in another app" or "Save a converted copy". |
| **Excel (Modern)** | `.xlsx` | Multi-sheet navigation tabs, inline strings (`<is><t>`), formula value evaluation with fallback, date formatting (`yyyy-mm-dd`). | In-place cell patching. Updates `<v>` values, preserves untouched formulas, number formats, column widths, and merged ranges. Updates `calcChain.xml` and marks `fullCalcOnLoad="1"`. |
| **Excel (Legacy)** | `.xls` | Detected via `D0CF11E0` OLE2 magic bytes. Extracted tabular text preview. | **Read-Only**: Disabled in-place saving. Limited preview banner with external app launcher. |
| **Spreadsheet** | `.csv` | RFC 4180 parsing, multi-line quotes, escaped commas. | Grid-based cell editing and RFC 4180 compliant CSV export. |
| **Presentations** | `.pptx`, `.ppt` | Slide preview carousel, slide text extraction, speaker notes. Legacy `.ppt` identified via OLE2 magic bytes. | Slide inspection and text copy. Saving is read-only. |
| **Text & Docs** | `.txt`, `.md`, `.rtf` | Charset autodetection (UTF-8, UTF-16, ASCII), RTF control word stripping, Markdown live preview. | Full-text editor with line count and UTF-8 safe persistence. |
| **Images** | `.jpg`, `.jpeg`, `.png`, `.webp` | High-resolution image viewer with pan, zoom, and orientation metadata support. | Viewing, sharing, and compilation into PDFs. |
| **Secure Vault** | `.vault` | Custom authenticated container (`ALLDOC_VAULT_V1`). | PBKDF2-HMAC-SHA256 (600,000 iterations), 16-byte random salt, 12-byte IV, and AES-256-GCM authenticated ciphertext. |
| **Scanner & OCR** | Physical Docs | ML Kit Document Scanner with automatic edge detection, perspective correction, and filters (B&W, Grayscale). Zero-GMS fallback via CameraX. | Multi-page reordering, on-device OCR text extraction, and export to searchable vector PDF (A4 format with invisible searchable text layer). |

---

## 🏗️ Architecture & Component Design

The codebase follows **Clean Architecture** and **Deep Module Design**:

```
com.alldoc.app
├── core
│   ├── data
│   │   ├── dao             # Room DAOs (DocumentDao, DocumentVersionDao)
│   │   ├── database        # AppDatabase with migration strategies
│   │   └── repository      # DocumentRepository (version retention, SAF integration)
│   ├── engine
│   │   ├── crypto          # DocumentCryptoEngine (PBKDF2-SHA256 600k iter + AES-256-GCM)
│   │   ├── format          # Magic-byte format sniffer (ZIP, OLE2, PDF, Images)
│   │   ├── ooxml           # DocxEngine, XlsxEngine, CsvEngine, PptxEngine
│   │   ├── ole2            # Ole2Engine (robust, bounds-checked binary parser)
│   │   ├── pdf             # PdfRendererEngine, PdfManipulator (PdfBox-Android)
│   │   ├── scanner         # DocumentScannerManager, OcrEngine, CameraFallback
│   │   ├── text            # TextEngine (RTF, Markdown, charset detection)
│   │   └── writer          # SafeDocumentWriter (backup, stage, hash verification, restore)
│   └── model               # Document, SpreadsheetModel, PdfAnnotations, SaveResult
└── presentation
    ├── home                # HomeScreen (Greeting, search, categories, carousel, bottom nav)
    ├── scanner             # ScanReviewScreen (filters, page thumbnails, OCR review)
    ├── security            # VaultPasswordDialog
    ├── theme               # AllDoc M3 Theme (#5B5BF0, opt-in dynamic color, typography)
    └── viewer              # DocumentViewerScreen & Coordinators
        ├── coordinator     # DocumentPersistenceCoordinator, PdfViewerCoordinator
        └── components      # SpreadsheetComponent, WordEditor, PdfViewer, UnsavedChangesDialog
```

### Deep Module Coordinators
To prevent God-class ViewModels, complex operations in `DocumentViewerViewModel` are delegated to cohesive coordinators:
- **`DocumentPersistenceCoordinator`**: Handles document saving, temp-file conversion, copy generation, and vault encryption.
- **`PdfViewerCoordinator`**: Handles PDF split, merge, and AES-256 password protection.
- **`SafeDocumentWriter`**: Encapsulates atomic-style disk staging, SHA-256 hash comparison, and automatic rollback on I/O failure.

---

## 🔒 Security & Privacy Engineering

- **No Internet Access**: The application does not declare `android.permission.INTERNET`. Zero telemetry, zero analytics, zero external network connections.
- **Scoped Storage & SAF**: Documents are accessed via explicit user grants (`ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`) with persisted permissions (`FLAG_GRANT_READ_URI_PERMISSION`, `FLAG_GRANT_WRITE_URI_PERMISSION`).
- **URI Validation**: Incoming intents are validated by `UriSecurityUtils` to ensure safe schemes (`content`, `file`) and prevent path traversal (`..`).
- **Narrow FileProvider**: Paths in `file_paths.xml` are restricted strictly to `scans/`, `camera_scans/`, `cached_docs/`, and `shared_exports/`.
- **System Backup Rules**: Android Cloud Backup (`backup_rules.xml` and `data_extraction_rules.xml`) explicitly excludes Room databases, version history files, and temporary caches to prevent leaking unencrypted user files to cloud backups.
- **Document Vault**: Encrypted documents use 600,000 iterations of PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM authenticated encryption. Tampered or corrupted vaults fail authentication before any decryption output is emitted.

---

## 🧪 Real-World Verification & Test Suite

All fixes and engines are validated through automated JUnit tests:

| Test Class | Focus Area | Status |
|---|---|---|
| `DocxEngineTest` | Word run-level DOM text patching, nested tables, headers/footers | ✅ PASS |
| `XlsxEngineTest` | Multi-sheet XML reading, inline strings, formula fallback, calcChain update | ✅ PASS |
| `CsvEngineTest` | RFC 4180 parsing, quotes, delimiter escaping | ✅ PASS |
| `TextEngineTest` | Charset sniffing, RTF stripping, 0-byte handling | ✅ PASS |
| `FormatSniffingTest` | Magic bytes sniffing (PK zip, OLE2 `D0CF11E0`, `%PDF`, JPEG, PNG, WEBP) | ✅ PASS |
| `Ole2EngineTest` | Legacy `.doc`, `.xls`, `.ppt` bounds-checked extraction | ✅ PASS |
| `SafeDocumentWriterTest` | Staged write, SHA-256 verification, restore on write failure | ✅ PASS |
| `DocumentCryptoEngineTest` | PBKDF2 600k iter, AES-256-GCM encryption, decryption & tampering rejection | ✅ PASS |
| `SnapshotRetentionPolicyTest` | Max 5 versions per file, 50MB global cache cap, cleanup on deletion | ✅ PASS |
| `UriSecurityUtilsTest` | Allowed schemes (`content`, `file`) and path traversal protection | ✅ PASS |
| `NavigationAndIntentTest` | Cold-start intent queueing and single-consumption | ✅ PASS |
| `DocumentViewerFlowInstrumentedTest` | Dirty-exit unsaved changes dialog and read-only blocking | ✅ PASS |

### Verification Artifacts
Test files saved and verified against real desktop readers (Word, Excel, Adobe Acrobat) are available in:
- `docs/verification/verified_saved_word.docx`
- `docs/verification/verified_saved_excel.xlsx`
- `docs/verification/verified_saved_pdf.pdf`

---

## 📦 Build & Release

### Prerequisites
- JDK 17
- Android SDK Platform 35 (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`)
- Gradle 8.7 (wrapper included)

### Build Commands
```bash
# Execute unit test suite
.\gradlew.bat testDebugUnitTest

# Assemble Debug APK (~60.9 MB)
.\gradlew.bat assembleDebug

# Assemble Production Release APK (~39.9 MB, R8 Full Mode, NDK ABI stripped)
.\gradlew.bat assembleRelease
```

### Release Signing
Release builds check for environment variables or `keystore.properties`:
- `KEYSTORE_PATH`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If not provided, the build safely falls back to the debug signing config for testing and CI pipelines.

---

## 📱 Manual Verification Checklist on Physical Device

To test the cold-start intent fix and real file opening on a physical device:
1. **Cold-Start Open from WhatsApp / File Manager**:
   - Force-stop AllDoc (`Settings > Apps > AllDoc > Force Stop`).
   - Open WhatsApp or your device file manager.
   - Tap any `.pdf`, `.docx`, `.xlsx`, or `.txt` file and select "Open with AllDoc".
   - **Expected**: AllDoc launches directly into the document viewer without a blank screen or dropped intent.
2. **Special Characters & Multi-Dot Filenames**:
   - Open a file named `100% report+final #2.pdf` or `my.report.v2.docx`.
   - **Expected**: Opens immediately; filename and extension are recognized correctly.
3. **Legacy OLE2 Binary Documents (`.doc`, `.xls`, `.ppt`)**:
   - Open a legacy `.doc` or `.xls` file.
   - **Expected**: Limited preview banner appears with an "Open In..." button; in-place saving is disabled to protect the file from corruption.
4. **Unsaved Changes Confirmation**:
   - Edit a document and tap the system back button without saving.
   - **Expected**: A 24dp rounded Apple modal appears asking to "Save Changes", "Discard", or "Cancel".
5. **Secure Vault Flow**:
   - Tap "Tools" or the Lock icon in the viewer, enter a password, and encrypt the document.
   - Reopen the `.vault` file.
   - **Expected**: Password prompt appears; entering the correct password decrypts and renders the document.

---

## 👥 Engineering Team
- **Elena Vance** — Tech Lead & Software Architect
- **Marcus Chen** — Senior Android & Core Systems Developer
- **Sophia Rossi** — Mobile Product & UI/UX Designer
- **Tariq Al-Mansoor** — Staff QA & Performance Engineer
- **Devin Brooks** — Senior Mobile AppSec & Privacy Engineer
