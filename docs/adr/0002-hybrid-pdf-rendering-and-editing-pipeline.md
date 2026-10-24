# ADR 0002: Hybrid PDF Rendering and Editing Pipeline

## Status
Accepted

## Context
PDF handling on Android requires two distinct capabilities with conflicting technical constraints:
1. **Rendering**: Rasterizing pages at 60fps with smooth pinch-to-zoom, fling scrolling, and sharp vector rendering at varying screen DPIs without exhausting GPU memory.
2. **Editing & Mutation**: Annotating (pen ink strokes, text annotations, highlighter, rectangular callouts), digital signature stamping, form filling, and structural operations (page reordering, deletion, splitting, merging).

Third-party C++ libraries (MuPDF, Foxit, PSPDFKit) either cost tens of thousands of dollars in commercial licensing or carry strict AGPL viral licensing that prohibits proprietary distribution. Pure Java PDF viewers without native hooks (like legacy PDFBox rendering) are sluggish, rendering pages at 1-3 seconds per page instead of 60fps.

## Decision
We adopt a **Dual-Engine Hybrid Pipeline**:
1. **Render Surface via Android OS `PdfRenderer`**:
   - Built into the Android runtime (`android.graphics.pdf.PdfRenderer`) since API 21, backed by the OS-level native C++ Skia / PDFium rasterizer.
   - Zero APK size impact.
   - Renders individual pages into high-resolution hardware-accelerated `Bitmap` instances on background coroutines (`Dispatchers.IO` / `Dispatchers.Default`).
   - Page cache layer with an LRU memory cache ensures smooth scrolling while capping heap usage.
2. **Annotation & Vector Overlay Layer in Jetpack Compose**:
   - Annotations (ink strokes, text boxes, highlights, signatures) are rendered in real time as a non-destructive vector overlay layer on top of the rendered page bitmap in Jetpack Compose `Canvas`.
   - Touch interactions are responsive at 120Hz display refresh rates.
3. **Save & Structural Operations via `PdfBox-Android` and Android `PdfDocument`**:
   - For exporting flattened annotated PDFs, Android's `PdfDocument` and `com.tom-roush:pdfbox-android` (Apache 2.0 license) flatten annotation vectors onto target page canvases.
   - For page operations (reordering, split, merge, page rotation, password protection), `PdfBox-Android` manipulates PDF page dictionaries directly without rasterization loss.

## Consequences

### Positive
- Sub-10ms page rendering times leveraging OS hardware acceleration.
- Zero licensing risk (Apache 2.0 + Android Platform APIs).
- Interactive, instantaneous annotation responsiveness in Compose.
- Lossless page reorganization and merging.

### Negative / Trade-offs
- Android's native `PdfRenderer` requires a seekable `ParcelFileDescriptor` (`MODE_READ_ONLY`), requiring remote or content URIs to be opened via `ContentResolver.openFileDescriptor`.
