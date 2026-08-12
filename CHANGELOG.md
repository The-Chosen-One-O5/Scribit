# Changelog

## 1.4.2

- Repairs the source-sync/update path so app source cannot silently stay old while only the version number changes.
- Verifies the exact `app/src` tree pushed to GitHub matches the local source tree before reporting success.
- Adds a CI source snapshot check so a release is rejected if critical source files do not match the release manifest.
- Re-ships the complete custom + multi-category UI: Add More, manual multi-category checkboxes, AI multi-category sorting, and legacy Other cleanup.

## 1.4.1

- Repairs the v1.4 category UI rollout so the actual app source is included in the update.
- Removes the legacy `Other` category from the library and database.
- Long-pressing a document now opens category checkboxes plus a delete action.
- Keeps multi-category assignment, custom categories, and AI category selection from v1.4.0.

## 1.4.0

- Replaced the fixed **Other** category with **Add More** so you can create your own categories such as Personal, Travel or Medical.
- Documents can now belong to **multiple categories at the same time**.
- AI classification receives the current category list and may assign every category that genuinely applies.
- Added manual **Manage categories** controls to document editing, so AI categorisation is never permanent or locked.
- Category filters now match any category assigned to a document.
- Existing Identity, Education, Career, Finance and Permits assignments migrate automatically during the update.
- Custom categories and multi-category assignments are included in Scribit backups.

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
