# ADR 0003: Google ML Kit Document Scanner with CameraX Fallback

## Status
Accepted

## Context
Scanning physical documents requires:
1. Fast real-time camera preview.
2. Robust quadrangular edge detection across textured backgrounds (wood, cloth, marble).
3. Perspective rectification (warping quadrilateral coordinates to a frontal rectangle).
4. Image enhancement filters (Color clean, Grayscale, B&W binary thresholding).
5. On-device Optical Character Recognition (OCR) for searchability and text selection.

Users hate requesting intrusive permissions (Play Store reviews show strong pushback against apps requiring broad CAMERA, LOCATION, and STORAGE permissions). In 2023, Google introduced the **Play Services ML Kit Document Scanner API** (`play-services-mlkit-document-scanner`), which runs the camera capture inside an isolated system intent with zero camera permission required from the host app, providing automated corner detection, shadow/stain removal, and filter adjustments.

## Decision
1. **Primary Scanner: Google ML Kit Document Scanner API**:
   - `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`
   - Zero runtime `CAMERA` permission required for the app.
   - User gets Google-grade edge detection, finger removal, auto-cropping, and multi-page capture.
   - Direct export to PDF and JPEG.
2. **On-Device OCR: Google ML Kit Text Recognition v2**:
   - `com.google.mlkit:text-recognition:16.0.1`
   - Executes 100% locally on the device (GPU/NNAPI delegate).
   - Generates searchable text overlays and indexes document content into local Room search tables.
3. **Fallback Scanner Pipeline: CameraX + Native Color Filters & Quad Warping**:
   - For AOSP, de-Googled devices, or environments where Google Play Services is absent, we provide an internal CameraX pipeline utilizing Android `Bitmap` matrix homography / OpenCV-compatible quad perspective correction and color thresholding filters.

## Consequences
- Unmatched user trust: App works out of the box without scary camera permission prompts on standard Google Play devices.
- Immediate professional scan quality with automated shadow and stain removal.
- 100% offline text recognition with zero cloud data transmission.
