# AllDoc Architecture & Implementation Plan

## 1. System Overview & Technology Stack

AllDoc is an offline-first Android application designed to view, edit, scan, and manage every major document format on Android 8.0 (API 26) through Android 15+.

- **Language**: Kotlin 1.9.22+ (100% Kotlin, Coroutines, StateFlow)
- **UI Framework**: Jetpack Compose (BOM 2024.04.01) with Material Design 3
- **Architecture**: Clean Architecture + MVI (Model-View-Intent / Unidirectional Data Flow)
- **Dependency Injection**: Constructor injection & factory patterns for modular decoupled testing
- **Local Persistence**: Room Database 2.6.1 (SQLite) with scoped storage SAF
- **Asynchrony**: Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Default`, `Dispatchers.Main.immediate`)
- **Document Engines**:
  - OpenXML Engine (Pure Kotlin, zero-dependency streaming XML & ZIP)
  - Hybrid PDF Engine (Android OS `PdfRenderer` + `PdfBox-Android` + Compose Vector Overlay)
  - Optical Capture & OCR (Google ML Kit Document Scanner API + ML Kit Text Recognition v2 + CameraX fallback)
  - Document Encryption (PBKDF2-HMAC-SHA256 600,000 iterations + AES-256-GCM authenticated encryption)
  - 100% Offline-First Architecture (Zero remote cloud uploads; sharing via Android System Share Sheet and Storage Access Framework)

---

## 2. Layered Clean Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                       │
│  Jetpack Compose Screens, MVI ViewModels, UI State & Events │
│  - Home / File Explorer (Recent, Favorites, Categories)     │
│  - Document Viewer & Editor (Word, Excel, PDF, PPT, Text)   │
│  - Camera / Document Scanner & OCR Review                   │
│  - Security & File Protection Vault                         │
└──────────────────────────────┬──────────────────────────────┘
                               │ Observes UI StateFlow & Emits Intents
┌──────────────────────────────▼──────────────────────────────┐
│                       Domain Layer                          │
│  Use Cases, Business Rules, Pure Kotlin Domain Entities     │
│  - OpenDocumentUseCase, SaveDocumentUseCase                 │
│  - ScanDocumentUseCase, PerformOcrUseCase                   │
│  - AnnotatePdfUseCase, MergeSplitPdfUseCase                 │
│  - EncryptDocumentUseCase, DecryptDocumentUseCase           │
│  - SearchDocumentsUseCase, ManageFavoritesUseCase           │
└──────────────────────────────┬──────────────────────────────┘
                               │ Implements Repository Interfaces
┌──────────────────────────────▼──────────────────────────────┐
│                        Data Layer                           │
│  Storage, Persistence, Parsers, External APIs               │
│  ├── DocumentRepository (Room DB + Scoped Storage SAF)      │
│  ├── OpenXmlEngine (DOCX, XLSX, PPTX, CSV)                  │
│  ├── PdfEngine (PdfRenderer, PdfBox-Android, PdfDocument)   │
│  ├── ScannerEngine (ML Kit Document Scanner + CameraX)      │
│  └── CryptoEngine (PBKDF2 + AES-256-GCM Vault Encryption)   │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Format Handling Specifications

### A. Word Documents (`.docx` / `.doc`)
- **Rendering**: Extracted paragraphs, styled text runs (bold, italic, underline, strike, colors, font sizes), unordered and ordered lists, tables with row/column structures, and embedded images.
- **Editing**:
  - In-place text editing, adding/deleting paragraphs.
  - Formatting modification (toggle bold/italic/underline, text color, heading level).
  - Table editing (modify cell text, insert row, delete row).
  - Image insertion from device gallery or camera scan.
- **Saving**: Streams original ZIP packaging, updates `word/document.xml`, retains all unedited XML parts (custom schemas, styles, headers, footers, macros) with zero file corruption.
- **Legacy `.doc`**: Binary OLE2 text and table extraction with seamless conversion/save to modern `.docx`.

### B. Spreadsheets (`.xlsx`, `.xls`, `.csv`)
- **Rendering**: Multi-sheet tab navigation, interactive grid coordinate rendering (A1..Z100), frozen header rows, cell formatting (currency, percentages, dates, colors).
- **Editing**:
  - Cell value input, formula bar (`SUM`, `AVERAGE`, `COUNT`, `MIN`, `MAX`, basic math expressions).
  - Add/delete sheet, rename sheet.
  - Column/row formatting and styling.
- **Saving**: Updates `xl/worksheets/sheet*.xml` and `xl/sharedStrings.xml` while preserving chart representations, pivot tables, and conditional formatting rules.

### C. PDF Documents (`.pdf`)
- **Rendering**: Native Android `PdfRenderer` hardware-accelerated tile rasterization. Pinch-to-zoom (1.0x to 5.0x), continuous vertical scroll, thumbnail drawer.
- **Editing & Annotations**:
  - Vector Freehand Ink (pen, pencil, calligraphy) with color, opacity, and stroke width control.
  - Vector Text Highlighter (semi-transparent blended highlight mode).
  - Text Note & Text Box placement with custom typography.
  - Digital Signature capture pad (drawn signature stamped onto page coordinates).
  - Form field editing (text fields, checkboxes).
  - Page operations: rotate page (90°/180°/270°), delete page, reorder pages, merge multiple PDFs, split ranges.
- **Saving**: Flattens annotations into the vector document stream using Android `PdfDocument` / `PdfBox-Android` without downsampling raster quality.

### D. Presentations (`.pptx`, `.ppt`)
- **Rendering**: Slide thumbnail carousel, full-screen slide viewer, text frames, shape rendering, presenter notes viewer.
- **Editing**: Slide note modifications, title/body text editing.

### E. Plain Text, Markdown, RTF & Images
- **Markdown (`.md`)**: Dual-mode (split/toggle) between raw Markdown source editor and rich rendered preview (headers, code blocks, lists, links, blockquotes).
- **Plain Text (`.txt`) & RTF (`.rtf`)**: Fast line-numbered text editor with encoding support (UTF-8, ISO-8859-1, UTF-16).
- **Images (`.jpg`, `.png`, `.webp`, `.bmp`)**: High-res bitmap viewer with pan/zoom, rotate, and export to PDF.

---

## 4. Mobile Engineering Realities & Honest Boundaries

1. **Desktop vs. Mobile Word Layout**:
   - Desktop Word calculates floating text wrapping around irregular 3D Bézier shapes across 100+ print pages. Attempting exact millimeter desktop page layout on a 6.2-inch mobile screen results in tiny, unreadable text requiring constant horizontal panning.
   - **AllDoc's Engineering Approach**: Mobile-optimized reflow view for reading and editing, alongside print layout preview. All complex non-text XML elements are preserved intact so when reopened on desktop Microsoft Word, the document layout remains 100% identical.
2. **Complex Excel Macro / VBA Execution**:
   - Running arbitrary Visual Basic for Applications (VBA) code on Android is insecure and fundamentally unsupported by mobile runtimes.
   - **AllDoc's Engineering Approach**: Evaluate standard mathematical and statistical formulas (`SUM`, `AVERAGE`, `PRODUCT`, `IF`, etc.) directly on device, while preserving VBA macro streams (`vbaProject.bin`) untouched in `.xlsm` / `.xlsx` files.

---

## 5. Development Milestones & Definition of Done (DoD)

### Milestone 1: Project Foundation, Core Models & Database
- Clean Architecture multi-layer module setup.
- Gradle build setup (Android 34, minSdk 26, Jetpack Compose, Material 3, Room, Coroutines).
- Room database schema (`DocumentEntity`, `DocumentVersionEntity`, `RecentDocumentDao`).
- Storage Access Framework (SAF) URI resolver and persistable permission manager.
- Unit tests for DB operations, version history, and URI resolvers.
- **DoD**: Project builds cleanly, all tests pass, database schema migrations verified.

### Milestone 2: Native OpenXML Engine (Word, Excel, PowerPoint, Text)
- `DocxEngine`: XML pull parser and in-place ZIP packaging mutator.
- `XlsxEngine`: Workbook, worksheet, shared strings, formula evaluator, and CSV reader/writer.
- `PptxEngine` & `MarkdownEngine`: Slide parser and Markdown renderer.
- Unit tests with mock DOCX/XLSX/CSV streams verifying zero-corruption saves.
- **DoD**: Unit test suite verifying reading, modifying, and saving sample documents without data corruption.

### Milestone 3: Hybrid PDF Engine & Annotation Canvas
- Native `PdfRenderer` memory-managed bitmap rasterizer with LRU cache.
- Compose Vector Annotation Canvas (pen ink, highlighter, text annotations, signature pad).
- `PdfManipulator`: Page reorder, rotate, delete, merge, split, and save with flattened annotations.
- Unit tests for PDF page manipulation and vector stroke serialization.
- **DoD**: Smooth 60fps PDF viewer and functional annotation-to-PDF export verified by tests.

### Milestone 4: Document Scanner & On-Device OCR Pipeline
- ML Kit Document Scanner API launcher with full edge detection, auto-crop, and color filters.
- Fallback CameraX pipeline with quad perspective warp.
- ML Kit Text Recognition v2 on-device OCR engine.
- Multi-page capture compilation to searchable PDF.
- **DoD**: Scanner produces clear multi-page PDFs with searchable OCR text.

### Milestone 5: UI/UX, Material You Design & Android Integration
- Material 3 theme (dynamic color support, light & dark theme, edge-to-edge).
- One-handed reachable bottom navigation and bottom action bars.
- File manager with category pills (PDF, Word, Excel, PPT, Scanned, Images), search, and favorites.
- Full `AndroidManifest.xml` intent-filters for "Open with AllDoc" from WhatsApp, Gmail, Downloads.
- Adaptive app icon, monochrome icon, vector logo, and Android 12+ Splash Screen.
- **DoD**: App handles external intent files cleanly, predictive back navigation works, TalkBack accessibility verified.

### Milestone 6: Security Vault, Cloud Sharing & Production Release Build
- AES-256-GCM file encryption / password protection vault.
- Android Share Sheet integration via `FileProvider`.
- Cloud upload integration architecture (Google Drive, OneDrive, Dropbox).
- ProGuard / R8 optimization rules, signed release keystore configuration.
- Three full critique-and-improve rounds by Senior Engineering team.
- Production Debug & Release APK generation.
- **DoD**: Zero linter errors, passing tests, hardened R8 build, APK ready for device installation.
