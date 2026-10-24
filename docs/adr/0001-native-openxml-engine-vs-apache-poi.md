# ADR 0001: Native Lightweight OpenXML Engine vs. Apache POI

## Status
Accepted

## Context
AllDoc requires robust viewing, editing, and saving capabilities for Microsoft Word (`.docx`), Excel (`.xlsx`), and PowerPoint (`.pptx`) documents, alongside legacy formats and plain text.
The industry standard in server-side Java is Apache POI or docx4j. However, mobile Android environments pose severe constraints:
1. **Missing `java.awt.*` Packages**: The Android Dalvik/ART runtime strips the Java AWT graphics stack (`java.awt.Color`, `java.awt.Font`, `java.awt.geom.*`), which Apache POI's core and rendering engines depend on. Using standard Apache POI produces `ClassNotFoundException` and `NoClassDefFoundError` crashes at runtime.
2. **Extreme Footprint**: Apache POI (ooxml, schemas, xmlbeans) adds 25MB–35MB to the final APK and over 120,000 methods, blowing past the Android DEX 64k limit and mandating heavy multi-dex overhead.
3. **Severe Memory Pressure & OOMs**: Apache POI parses entire XML DOMs into memory. On typical Android devices with restricted 192MB–256MB app heap limits, opening a 2MB Word document with multiple images or an Excel sheet with 5,000 rows causes an immediate `OutOfMemoryError`.
4. **Fidelity Destruction**: Heavy third-party converters attempt to re-serialize the entire OpenXML package, frequently discarding unknown tags, custom XML parts, VBA macros, charts, and relationship headers.

## Decision
We implement a **Native Kotlin OpenXML Engine (`AllDoc OpenXML Engine`)** operating directly on the ISO/IEC 29500 (ECMA-376) Open Packaging Conventions:
- **Format Architecture**: DOCX, XLSX, and PPTX are ZIP archives containing hierarchical XML streams (`word/document.xml`, `xl/worksheets/sheet1.xml`, etc.).
- **Streaming Parser & Modifier**: We use streaming `ZipInputStream` and Android's native high-performance `XmlPullParser` / lightweight SAX to parse paragraphs, runs, fonts, styles, tables, cell values, formulas, and slide elements without loading the full uncompressed archive into memory.
- **In-Place Zip Part Mutation**: When modifying a document, we only unpack and re-serialize the specific modified XML part (e.g. `word/document.xml` or `xl/worksheets/sheet1.xml`). All other parts (`[Content_Types].xml`, `_rels/.rels`, `word/styles.xml`, embedded media, macros, themes, charts) are streamed untouched byte-for-byte.
- **Zero Third-Party Dependency**: 100% Android-native Kotlin. Total footprint under 200KB.

## Consequences

### Positive
- **Instant Cold Starts & Zero OOMs**: Memory footprint stays under 15MB during typical document operations.
- **Zero APK Bloat**: Saves over 30MB of APK size compared to Apache POI.
- **Complete Preservation**: Because untouched parts are copied byte-for-byte in the ZIP stream, macros, custom XML, fonts, and complex styling never get corrupted or stripped when saving.
- **Full Offline Operation**: Does not require any cloud rendering servers or network connection.

### Negative / Trade-offs
- Advanced layout rendering (e.g., complex wrapping around floating 3D shapes or WordArt) is simplified into clean sequential mobile-friendly visual representation rather than 100% desktop print layout parity.
- Requires explicit unit testing for XML edge cases and namespace declarations.
