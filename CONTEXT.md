# Domain Context: AllDoc

This document defines the canonical domain vocabulary, ubiquitous language, and business concepts for the AllDoc system. It contains strictly domain terminology without implementation details, technical file paths, or framework-specific APIs.

---

## 1. Document Lifecycle & Entities

### Document
An immutable or version-tracked digital asset representing written, tabular, visual, or presentation content. Every document possesses an intrinsic content format, display metadata, security state, and storage origin.

### Document Format
The structural encoding specification of a document:
- **Word Processing**: Flowing text organized into sections, paragraphs, runs, styling spans, inline tables, and embedded images.
- **Spreadsheet**: Grid-oriented workbook comprising named worksheets, addressed cells containing raw values, formulas, or formatting rules, and evaluation dependencies.
- **Fixed-Layout (PDF)**: Absolute vector- and raster-positioned pages maintaining strict visual fidelity, containing content streams, annotation layers, interactive form fields, and digital signatures.
- **Presentation**: Slide sequence comprising visual frames, typography hierarchy, shapes, notes, and layout templates.
- **Plain / Structured Text**: Lightweight unformatted or markup-delimited character sequences (Plain Text, Markdown, RTF, CSV).
- **Raster Image**: Pixel matrix captured from camera or imported as a visual document.

### Document Session
An active user interaction context with a single document. Tracks transient modifications, undo/redo history, zoom and scroll coordinates, and view modes.

### Document Revision & Snapshot
An immutable point-in-time preservation of a document's content and structural integrity before or after a modification. Enables revision rollback, difference detection, and automatic recovery in case of abrupt interruption.

---

## 2. Editing & Annotation Domain

### Semantic Preservation
The property that editing an editable region of a complex document does not alter, corrupt, strip, or invalidate unedited structures (such as embedded macros, themes, charts, headers, footers, relationships, or non-standard XML payloads).

### Run
The smallest contiguous span of text within a paragraph that shares an identical set of typographical formatting attributes (font family, size, weight, slant, decoration, foreground color, background highlight).

### Cell Evaluation
The resolution of an expression (arithmetic operation, statistical function, cell reference) within a worksheet into a concrete computed display value.

### Annotation Layer
A non-destructive visual overlay positioned on top of a fixed-layout document page. Includes freehand ink strokes, highlighted text spans, sticky notes, text boxes, shapes, and signature stamps.

### Digital Signature Stamp
A cryptographically verifiable or visual hand-drawn attestment placed at specific coordinates of a document page, binding the signer's approval to the document state.

---

## 3. Physical Capture & Optical Domain

### Document Capture
The optical acquisition of a real-world document via camera sensor.

### Boundary Detection
The algorithmic identification of the four physical corners of a paper document within a camera frame against arbitrary backgrounds and lighting conditions.

### Perspective Rectification
The geometric transformation mapping an arbitrary quadrangular perspective projection of a document into a flat, frontal, rectangular coordinate space with corrected aspect ratio.

### Scan Enhancement Filter
A pixel transformation pipeline that cleans a captured document:
- **Magic / Color Enhanced**: Brightens background paper white balance while preserving colored inks and stamps.
- **Grayscale**: Removes chromatic color noise and flattens paper texture into continuous gray levels.
- **B&W / Binary**: High-contrast thresholding that converts ink to pure black and background paper to pure white for crisp text and minimal storage size.

### Optical Character Recognition (OCR)
The automated transcription of glyphs and text lines from a raster document into structured, searchable unicode text, indexed with bounding coordinates.

---

## 4. Storage & Security Domain

### Storage Origin
The authority hosting the underlying document data:
- **Local Device Storage**: The physical non-volatile storage of the mobile device.
- **System Content Authority**: Content provided via a system content URI without direct file path exposure (Storage Access Framework).
- **System Share Dispatch**: Documents shared out of the application via the standard Android system share sheet. AllDoc is strictly 100% offline-first; no remote third-party cloud uploads or external API tracking.

### Document Vault & Encryption
A security boundary where document payload bytes are transformed using symmetric authenticated encryption keys (AES-256-GCM) derived from user passwords via PBKDF2, preventing unauthorized inspection.

### Conflict Resolution
The deterministic handling of concurrent updates when a local document revision diverges from an external or cloud document revision.
