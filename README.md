# AllDoc — Minimalist Universal Mobile Document Suite for Android

<p align="center">
  <strong>One native Android app to open, view, edit, scan, and secure every document.</strong><br>
  Targeting Android 8.0 (API 26) through Android 15 (API 35). 100% Native Kotlin, Jetpack Compose, Material 3, and strictly Offline-First.
</p>

---

## 🏛️ Executive Summary & Design Principle

**AllDoc** is an offline-first Android document suite designed with a single guiding principle: **Less, but better** (inspired by Dieter Rams, Apple Notes, Things, Linear, and early Google Keep).

1. **Minimalist, Typographic UI**: Calm, quiet, fast. Near-monochrome palette (Light: `#FFFFFF`/`#FAFAFA`, Dark: `#0B0B0C`/`#141416`) with a single restrained indigo accent (`#5B5BF0`). No gradients, drop shadows, carousels, or decorative badges. Hierarchy is achieved purely through typography (Inter scale) and 1dp hairline dividers.
2. **Modular Architecture**: Clean, decoupled composables. The home screen is broken down into focused components (`HomeTopBar`, `HomeSearchBar`, `DocumentRowItem`, `HomeActionSheet`, `HomeSettingsSheet`, `HomeEmptyState`), keeping the main screen under 300 lines.
3. **100% Offline-First & Private**: Zero network permissions declared (`android.permission.INTERNET` is not requested). Documents and camera scans never leave the device.
4. **Lossless In-Place Mutation**: Edits to `.docx` patch only modified `<w:t>` runs in the XML DOM; edits to `.xlsx` patch only modified cell `<v>` tags while preserving formulas, number formatting, styles, and sheet structures.
5. **Safe, Verified Disk Writing**: `SafeDocumentWriter` backs up files to cache before writing, stages changes, and validates writes via size and SHA-256 hash comparison. If verification fails, the original is restored.
6. **Cold-Start Intent Handling**: Documents opened from WhatsApp, Gmail, Chrome, or file managers are captured in state and dispatched reactively once the Compose navigation host mounts, eliminating dropped cold-start intents.

---

## 📱 Format Support & Honest Feature Capabilities

| Document Category | Formats | Real-World Capabilities | In-App Edit & Mutation Fidelity |
|---|---|---|---|
| **PDF** | `.pdf` | Hardware-accelerated page rendering with memory-budgeted `LruCache` (capped at 64MB) and bitmap recycling. View password-protected PDFs. Default distraction-free reading mode. | Add ink drawings, semi-transparent multiply-blended highlights, and TrueType Unicode text notes (supports accented Latin, Chinese, Arabic). Split page ranges, merge documents, and AES-256 password-protect. Annotations flattened vectorially via PdfBox-Android. |
| **Word (Modern)** | `.docx` | Paragraphs, headings, bullet lists, nested tables, images. | In-place run-level DOM text patching. Preserves unedited runs, bold/italic formatting, hyperlinks, headers/footers, and document relationships. |
| **Word (Legacy)** | `.doc` | Detected via `D0CF11E0` OLE2 magic bytes. Extracted text preview. | **Read-Only**: In-place saving is disabled to prevent corruption. Users can "Open in another app" or "Save a copy". |
| **Excel (Modern)** | `.xlsx` | Multi-sheet navigation tabs, inline strings (`<is><t>`), formula value evaluation with fallback, date formatting (`yyyy-mm-dd`). | In-place cell patching. Updates `<v>` values, preserves untouched formulas, number formats, column widths, and merged ranges. Updates `calcChain.xml` and marks `fullCalcOnLoad="1"`. |
| **Excel (Legacy)** | `.xls` | Detected via `D0CF11E0` OLE2 magic bytes. Extracted tabular text preview. | **Read-Only**: Disabled in-place saving. Limited preview banner with external app launcher. |
| **Spreadsheet** | `.csv` | RFC 4180 parsing, multi-line quotes, escaped commas. | Grid-based cell editing and RFC 4180 compliant CSV export. |
| **Presentations** | `.pptx`, `.ppt` | Slide preview carousel, slide text extraction, speaker notes. Legacy `.ppt` identified via OLE2 magic bytes. | Slide inspection and text copy. Read-only. |
| **Text & Docs** | `.txt`, `.md`, `.rtf` | Charset autodetection (UTF-8, UTF-16, ASCII), RTF control word stripping, Markdown live preview. | Full-text editor with line count and UTF-8 safe persistence. |
| **Images** | `.jpg`, `.jpeg`, `.png`, `.webp` | High-resolution image viewer with pan, zoom, and orientation metadata support. | Viewing, sharing, and compilation into PDFs. |
| **Secure Vault** | `.vault` | Password-protected encrypted container (`ALLDOC_VAULT_V1`). | **Password-derived, not hardware-backed**: PBKDF2-HMAC-SHA256 (600,000 iterations), 16-byte random salt, 12-byte IV, and AES-256-GCM authenticated ciphertext. |
| **Scanner & OCR** | Physical Docs | Google Play Services ML Kit Document Scanner with automatic edge detection, perspective correction, and filters (B&W, Grayscale). | Multi-page reordering, on-device OCR text extraction, and export to searchable vector PDF (A4 format with invisible searchable text layer). |
| **Scanner Fallback** | Physical Docs | **Basic photo fallback**: Uses standard `TakePicture` camera capture on devices without Google Play Services. | Captures single or multi-page photos without automatic edge detection or perspective warping (offline perspective homography without GMS is not implemented). |
| **Cloud Storage** | Drive, Dropbox, OneDrive | Native Android Share Sheet & Storage Access Framework (`Save to…`). | **No direct cloud API integration**: Direct cloud SDKs (Google Drive API, Dropbox SDK, OneDrive API) are not implemented. Uploads and exports are performed via system Share Sheet and SAF picker. |

---

## 🎨 Minimalist Design System

Detailed design documentation is available in **[`docs/design-system.md`](docs/design-system.md)**.

- **Color**:
  - Light: Background `#FFFFFF`, Primary Text `#111111`, Secondary Text `#6B6B6B`, Hairline dividers `#ECECEC`.
  - Dark: Background `#0B0B0C`, Surface `#141416`, Primary Text `#F2F2F2`, Secondary Text `#8A8A8E`, Hairlines `#232326`.
  - Accent: Single restrained indigo (`#5B5BF0`) used only for the primary action button, active states, and links.
  - Dynamic Color (Material You): Disabled by default to keep the quiet aesthetic; opt-in in Settings.
- **Typography Scale**:
  - Large Title: 28sp (Bold/SemiBold, tight tracking `-0.75sp`)
  - Title Medium: 15sp (SemiBold)
  - Section Label: 11-13sp (Medium, secondary color, `+0.5sp` tracking)
  - Body: 16sp (Normal, line height `24sp`)
  - Meta: 13sp (Normal, secondary color)
- **Surfaces**:
  - 0dp elevation. Flat surfaces separated by generous whitespace and 1dp hairline dividers.
  - Radii: 12dp for inputs/sheets/dialogs, 8dp for buttons/chips.

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
    ├── home                # HomeScreen & modular components
    │   └── components      # HomeTopBar, HomeSearchBar, DocumentRowItem, HomeActionSheet, HomeSettingsSheet, HomeEmptyState
    ├── scanner             # ScanReviewScreen (filters, page thumbnails, OCR review)
    ├── security            # VaultPasswordDialog (Password-derived AES-256-GCM)
    ├── theme               # Minimalist M3 Theme (Near-monochrome, Inter scale)
    └── viewer              # DocumentViewerScreen & Coordinators
        ├── coordinator     # DocumentPersistenceCoordinator, PdfViewerCoordinator
        └── components      # SpreadsheetComponent, WordEditor, PdfViewer, UnsavedChangesDialog
```

---

## 🔒 Security & Privacy Engineering

- **No Internet Access**: The application does not declare `android.permission.INTERNET`. Zero telemetry, zero analytics, zero external network connections.
- **Scoped Storage & SAF**: Documents are accessed via explicit user grants (`ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`) with persisted permissions.
- **Password-Derived Document Vault**: Encrypted documents use 600,000 iterations of PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM authenticated encryption. The key is derived directly from the user's password (it is **not** hardware-backed by Android KeyStore).
- **URI Validation**: Incoming intents are validated by `UriSecurityUtils` to ensure safe schemes (`content`, `file`) and prevent path traversal (`..`).
- **Narrow FileProvider**: Paths in `file_paths.xml` are restricted strictly to `scans/`, `camera_scans/`, `cached_docs/`, and `shared_exports/`.
- **System Backup Rules**: Android Cloud Backup (`backup_rules.xml` and `data_extraction_rules.xml`) explicitly excludes Room databases, version history files, and temporary caches to prevent leaking user files to cloud backups.

---

## 🧪 Test Verification & Quality Assurance

### 1. Unit Tests (Verified & Passing on JVM)
Run with `.\gradlew.bat testDebugUnitTest`:

| Test Class | Focus Area | Status |
|---|---|---|
| `DocxEngineTest` | Word run-level DOM text patching, nested tables, headers/footers | ✅ **PASS** |
| `XlsxEngineTest` | Multi-sheet XML reading, inline strings, formula fallback, calcChain update | ✅ **PASS** |
| `CsvEngineTest` | RFC 4180 parsing, quotes, delimiter escaping | ✅ **PASS** |
| `TextEngineTest` | Charset sniffing, RTF stripping, 0-byte handling | ✅ **PASS** |
| `FileSnifferTest` | Magic bytes sniffing (PK zip, OLE2 `D0CF11E0`, `%PDF`, JPEG, PNG, WEBP) | ✅ **PASS** |
| `SampleFilesTest` | Real-world format matrix (PDF, password PDF, DOCX with images, DOC, XLS, multi-sheet XLSX, inline strings, PPTX, PPT, CSV, MD, RTF, TXT, images) | ✅ **PASS** |
| `Ole2EngineTest` | Legacy `.doc`, `.xls`, `.ppt` bounds-checked extraction | ✅ **PASS** |
| `SafeDocumentWriterTest` | Staged write, SHA-256 verification, restore on write failure | ✅ **PASS** |
| `DocumentCryptoEngineTest` | PBKDF2 600k iter, AES-256-GCM encryption, decryption & tampering rejection | ✅ **PASS** |
| `SnapshotRetentionPolicyTest` | Max 5 versions per file, 50MB global cap, cleanup on deletion | ✅ **PASS** |
| `UriSecurityUtilsTest` | Allowed schemes (`content`, `file`) and path traversal protection | ✅ **PASS** |
| `NavigationAndIntentTest` | Cold-start intent queueing and single-consumption | ✅ **PASS** |

### 2. Instrumented Tests (Verified on Device: TECNO KG5p, Android 12)
Run on connected hardware via ADB (`adb shell am instrument -w -r com.alldoc.app.debug.test/androidx.test.runner.AndroidJUnitRunner` or `.\gradlew.bat connectedDebugAndroidTest`):

| Instrumented Test Class | What It Tests | Status |
|---|---|---|
| `ColdStartIntentTest` | Verifies cold-start `ACTION_VIEW` and `ACTION_SEND` intents navigate directly to document viewer without dropping intents or crashing. | ✅ **PASS (2/2)** |
| `DocumentViewerFlowInstrumentedTest` | Verifies dirty-exit unsaved changes prompt and read-only save blocking on the UI thread. | ✅ **PASS (2/2)** |

---

## 📦 Build & Keystore Configuration

### Prerequisites
- JDK 17
- Android SDK Platform 35 (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`)
- Gradle 8.7 (wrapper included)

### Building the APKs
```bash
# Execute unit test suite
.\gradlew.bat testDebugUnitTest

# Compile instrumented tests
.\gradlew.bat compileDebugAndroidTestKotlin

# Assemble Debug APK (~60.9 MB)
.\gradlew.bat assembleDebug

# Assemble Production Release APK (~39.9 MB, R8 Full Mode, NDK ABI stripped)
.\gradlew.bat assembleRelease
```

### Creating a Production Keystore
To sign a release APK for production distribution, generate a keystore using Java `keytool`:

```bash
keytool -genkey -v -keystore release.keystore -alias alldoc -keyalg RSA -keysize 2048 -validity 10000
```

Configure your signing credentials using either environment variables:
```bash
export KEYSTORE_PATH="/path/to/release.keystore"
export KEYSTORE_PASSWORD="your-keystore-password"
export KEY_ALIAS="alldoc"
export KEY_PASSWORD="your-key-password"
```
Or create a `keystore.properties` file in the project root:
```properties
storeFile=/path/to/release.keystore
storePassword=your-keystore-password
keyAlias=alldoc
keyPassword=your-key-password
```

> ⚠️ **Build Warning**: If `KEYSTORE_PATH` or `keystore.properties` is not found, Gradle emits a prominent warning and falls back to signing with the debug key for developer convenience. **Do not publish release APKs signed with the debug key.**

---

## 📱 Manual Verification Checklist on Physical Device

To test cold-start "Open with" and real document workflows on your device:
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
   - **Expected**: A quiet notification banner indicates *"Legacy binary format. Extracted read-only text."*; direct saving is disabled, offering *"Open In…"*.
4. **Save to Storage / Cloud**:
   - In the Home Screen row menu or Viewer options menu, tap *"Save to… (Export)"*.
   - **Expected**: Opens the Android Storage Access Framework picker, allowing you to save the file to Google Drive, OneDrive, Dropbox, or local storage.
5. **Unsaved Changes Confirmation**:
   - Edit a document in Word or Spreadsheet mode and tap the back arrow without saving.
   - **Expected**: A 12dp flat dialog prompts *"Save changes before exiting?"* with plain text buttons *"Save"*, *"Discard"*, and *"Cancel"*.
6. **Password Vault Flow**:
   - In the viewer, select *"Encrypt to Vault"*, enter a password, and encrypt the document.
   - Reopen the `.vault` file.
   - **Expected**: The password prompt appears; entering the correct password decrypts and renders the document.

---

## 👥 Engineering Team
- **Elena Vance** — Tech Lead & Software Architect
- **Marcus Chen** — Senior Android & Core Systems Developer
- **Sophia Rossi** — Mobile Product & UI/UX Designer
- **Tariq Al-Mansoor** — Staff QA & Performance Engineer
- **Devin Brooks** — Senior Mobile AppSec & Privacy Engineer
