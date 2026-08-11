# Changelog

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
