# Changelog

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

- Switched GitHub releases from temporary debug signing to a permanent release-signing workflow.
- Added portable Scribit backup and restore.
- Backups include archived document copies, metadata and non-secret settings while excluding API keys.
- Added restore access to the fresh-install setup screen.
- Added incremental database migration structure; updates no longer rely on destructive schema recreation.
- Added About/version information in Settings.
- Kept long-press document deletion introduced in 1.1.
- Kept local SHA-256 duplicate detection and the red duplicate warning introduced in 1.1.
- GitHub releases now use semantic version tags and publish a verified signed `Scribit.apk`.

## 1.1.0

- Added long-press deletion with confirmation.
- Added exact duplicate detection with local SHA-256 hashing.
- Added duplicate protection for imports, shares and scans.

## 1.0.0

- Initial Scribit Android prototype.
- Local archive, AI classification, search, review flow, themes and GitHub APK builds.
