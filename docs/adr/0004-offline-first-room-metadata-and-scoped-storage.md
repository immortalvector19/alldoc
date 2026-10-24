# ADR 0004: Offline-First Room Architecture and Scoped Storage

## Status
Accepted

## Context
Android 10+ (API 29+) and Android 11+ (API 30+) enforce Scoped Storage, restricting broad raw filesystem access (`/sdcard`) and prohibiting deprecated external storage permissions. Furthermore, users frequently open documents directly from external apps (WhatsApp, Gmail, Downloads, File Manager, Browser) via Content URIs (`content://...`).

## Decision
1. **System Integration via Storage Access Framework (SAF) & Intent Filters**:
   - Register explicit `<intent-filter>` declarations in `AndroidManifest.xml` with `ACTION_VIEW` and `ACTION_EDIT` for all supported MIME types (`application/pdf`, `application/vnd.openxmlformats-officedocument.*`, `application/msword`, `application/vnd.ms-excel`, `text/plain`, `text/csv`, `text/markdown`, `image/*`).
   - Open all documents via `ContentResolver.openInputStream` / `openOutputStream` with persistent URI permissions (`takePersistableUriPermission`).
2. **Offline-First Indexing via Room Database**:
   - `DocumentEntity`: stores URI, filename, MIME type, size, last accessed timestamp, last modified timestamp, favorite flag, thumbnail cache path, and encrypted flag.
   - `DocumentVersionEntity`: stores auto-saved point-in-time snapshot references in app-private cache storage for crash recovery and version rollback.
   - Full-text search (FTS4/FTS5) table in Room for instant offline search across scanned OCR text and parsed document contents.
3. **Security by Default**:
   - Zero telemetry tracking.
   - Optional local AES-256-GCM hardware encryption for sensitive documents using Android KeyStore.
   - Cloud uploads (Google Drive, OneDrive, Dropbox) are strictly user-initiated via explicit OAuth 2.0 PKCE actions or Android Share Sheet.

## Consequences
- 100% compliance with Google Play Store Scoped Storage policies.
- Seamless "Open with AllDoc" from WhatsApp, Gmail, Slack, and Chrome.
- Reliable offline operation with instant recent document list and full-text search.
