# Changelog

## 1.3.2

- Reworked duplicate detection so it uses only exact SHA-256 file-content hashes; similar filenames never count as duplicates.
- Renamed byte-for-byte copies are correctly detected because filenames are irrelevant to the hash.
- Duplicate imports are no longer blocked or automatically deleted.
- Exact duplicate documents are highlighted in red across List, Compact and Grid layouts.
- Added **Keep this copy** to dismiss an intentional duplicate warning while leaving the document in Scribit.
- Deleting one copy automatically clears the warning on the final remaining copy.
- Backup/restore now preserves intentional duplicate documents instead of dropping them by content hash.
- Simplified About Scribit to show only version and build.
- Simplified public GitHub Release notes so they no longer expose maintainer-only signing commentary.

## 1.3.1

- Added persistent library layout switching from the Library header.
- Added comfortable List, denser Compact, and two-column Grid views.
- The selected layout is remembered across app restarts and included in portable Scribit backups.
- Long-press delete, queue/retry status, search filters and document opening work in every layout.

## 1.3.0

- Replaced bursty per-document AI jobs with one persistent serial processing queue.
- New imports appear immediately as queued and continue processing in the background.
- Added automatic retry for HTTP 429 rate limits and temporary provider/network failures.
- Respects provider `Retry-After` hints when available and uses exponential WorkManager backoff otherwise.
- Added queued, processing and auto-retrying states in the library and document detail UI.
- Added a queue status card showing waiting/processing/retrying counts and the next retry time.
- Queue state survives app closure and device scheduling because it is backed by WorkManager.
- Added database migration v4 for retry timestamps/counts without deleting existing documents.
- Deleting a queued document no longer cancels the WorkManager chain behind it; the stale job becomes a safe no-op.

## 1.2.0

- Enabled normal in-place Android updates for the signed release line.
- Added portable Scribit backup and restore.
- Backups include archived document copies, metadata and non-secret settings while excluding API keys.
- Added restore access to the fresh-install setup screen.
- Added incremental database migration structure; updates no longer rely on destructive schema recreation.
- Added About/version information in Settings.
- Kept long-press document deletion introduced in 1.1.
- Kept local SHA-256 duplicate detection and the red duplicate warning introduced in 1.1.
- GitHub releases use semantic version tags and publish `Scribit.apk`.

## 1.1.0

- Added long-press deletion with confirmation.
- Added exact duplicate detection with local SHA-256 hashing.
- Added duplicate protection for imports, shares and scans.

## 1.0.0

- Initial Scribit Android prototype.
- Local archive, AI classification, search, review flow, themes and GitHub APK builds.
