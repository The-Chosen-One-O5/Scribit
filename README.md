<p align="center">
  <img src="docs/scribit-logo.svg" alt="Scribit" width="620" />
</p>

<p align="center">
  A private, phone-first document archive for Android.<br/>
  Messy files in. Useful documents out.
</p>

<p align="center">
  <a href="https://github.com/The-Chosen-One-O5/Scribit/releases/latest/download/Scribit.apk">
    <img alt="Download Scribit APK" src="https://img.shields.io/badge/Download-Scribit.apk-6558E8?style=for-the-badge&logo=android&logoColor=white" />
  </a>
  &nbsp;
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
</p>

## What is Scribit?

My phone has never had a *document storage* problem. It has had a *finding the document later* problem.

A marksheet becomes `scan_29491.pdf`. A certificate sits in Downloads next to memes and random screenshots. Something important arrives through WhatsApp and six months later I remember what it looked like, but not what the file was called.

Scribit is a small Android app I built around that problem.

You import or share a PDF, image, or text document. Scribit keeps its own private archive copy, asks your OpenAI-compatible model to understand the document, then stores useful metadata locally so you can find it again without memorising filenames.

The AI never gets permission to rename, move, overwrite, or delete the original file.

## Features

- Import **PDFs, images and text files** with Android's file picker.
- Use **Share → Scribit** from WhatsApp, Files, browsers and other Android apps.
- Take a quick document photo with the system camera.
- Use your own **OpenAI-compatible API** instead of being tied to a Scribit account.
- Extract useful metadata such as:
  - title
  - category
  - document type
  - organisation / institution
  - issue date
  - expiry date
  - academic year / semester
  - tags
  - summary
  - extra search terms
- Search locally with SQLite full-text search.
- Use **Smart Search** when you remember the idea rather than the exact words.
- Put uncertain AI results into **Needs Review** instead of quietly pretending they are correct.
- Edit metadata by hand or re-run classification.
- Long-press a library item to **delete it from Scribit**. The source file outside Scribit stays untouched.
- Detect **exact duplicate files locally with SHA-256** before running AI again.
- Show a clear red duplicate warning instead of storing the same file twice.
- Track expiry dates and schedule expiry notifications.
- Choose **System, Light or Dark** appearance.
- Create a portable **Scribit backup ZIP** and restore it later.
- Import many documents at once without blasting the API: Scribit uses a **serial background queue**.
- Automatically recover from normal API **429 rate limits** and temporary network/provider errors.

## Install

### Normal installation

- Tap **Download Scribit APK** at the top of this page.
- Download `Scribit.apk` from the latest GitHub Release.
- Open the APK on your Android phone.
- If Android asks, allow your browser or file manager to install apps from that source.
- Install Scribit.

Scribit currently targets Android 8.0 and newer.

### Updating Scribit

Official GitHub Releases are signed with the same permanent Scribit signing key.

That means normal future updates should be simple:

- Download the newer `Scribit.apk`.
- Open it.
- Android should offer **Update** rather than asking you to uninstall the current app.
- Tap **Update**.
- Your local Scribit database, settings and archive remain in place.

Do **not** uninstall Scribit just to install a normal update. Uninstalling an Android app removes its private app data.

> Very early Scribit debug builds used temporary debug certificates. Those old builds cannot be upgraded into the permanently signed release line because Android sees them as a different signer. That was a one-time project setup mistake; releases from the permanent signing setup onward use the same key.

## First launch

Scribit asks for:

- **API base URL** — for example `https://provider.example/v1`
- **API key**
- **Model**
- **Vision support** — enable this when the model can understand images

You can test the connection before saving it.

The API key is encrypted using Android Keystore before it is stored locally.

## Using Scribit

### Add a document

Use whichever route is quickest:

- **Share:** open the document in another app → Share → Scribit.
- **Import:** open Scribit → Add document.
- **Scan:** use the camera button inside Scribit.

Scribit calculates the file's SHA-256 hash locally first. If that exact file is already in the library, the import stops immediately and shows a duplicate warning. No AI request is spent on that check.

For a new file, classification is queued through Android WorkManager. Scribit does not keep a terminal, Python process, polling loop, or permanent background service running.

Scribit processes document AI jobs **one at a time** instead of firing a burst of requests when you import a folder. If the provider returns HTTP 429, Scribit pauses that queue and retries automatically. It follows `Retry-After` when the provider sends one; otherwise WorkManager uses exponential backoff. Temporary timeouts and common 5xx provider errors also retry automatically. The document stays visible in the library as **Queued** or **Retrying automatically**, so there is normally nothing to tap.

### Delete a document

- Long-press the document in the library.
- Confirm **Delete**.

This removes:

- Scribit's private archived copy
- the local database record
- the local search entry

It does **not** delete the original file you imported from Downloads, WhatsApp, Drive, another file manager, etc.

## Backup & restore

Open **Settings → Backup & restore**.

### Back up

- Tap **Back up**.
- Pick where you want to save the generated `.zip` file.
- Keep that ZIP somewhere you control.

A Scribit backup contains:

- Scribit's private document copies
- extracted metadata
- tags and summaries
- dates and classifications
- document status
- non-secret app settings such as provider URL, model, vision setting and theme

The backup deliberately does **not** contain your API key.

### Restore

You can restore from either:

- **Settings → Backup & restore → Restore**, or
- the **Restore Scribit backup** button on the first-launch screen of a fresh installation

Restore merges documents into the current library. Exact file duplicates are skipped using the same local SHA-256 check.

The backup format is versioned independently from the SQLite database. Scribit rebuilds its database records from the backup manifest instead of blindly replacing the live database file, which gives future app versions room to migrate their schema safely.

After restoring onto a fresh phone/install, re-enter your API key because that secret is intentionally device-local.

## Privacy notes

A few rules are intentionally boring because the files may not be:

- The AI does not rename, move, overwrite, or delete your original documents.
- Scribit works from a private app-owned archive copy.
- Your API key is encrypted with Android Keystore.
- The API key is excluded from portable backups.
- Exact duplicate detection happens locally with SHA-256.
- Search/index data lives in the local SQLite database.
- AI content is sent only to the API provider you configure when AI work is requested.
- There is no built-in Scribit cloud account or Scribit cloud sync.
- Android automatic app backup is disabled; portable backup is an explicit action you control.

## API compatibility

Scribit currently targets OpenAI-style `POST /v1/chat/completions` APIs.

- If the base URL ends in `/v1`, Scribit adds `/chat/completions`.
- If you enter the complete `/chat/completions` endpoint, Scribit uses it as-is.
- Vision classification uses image data URLs in the common OpenAI-compatible message format.
- PDFs are rendered into page images for vision classification.
- Text-only models can still classify text files; image/PDF metadata can be edited manually when Vision is disabled.

## Building the project

### GitHub Actions

Every push to `main` runs **Build & Publish Signed Scribit APK**.

The workflow:

- checks out the source
- installs Java 17
- installs Android SDK 36 / Build Tools 36
- restores the private signing keystore from GitHub Actions secrets
- builds the **release** variant
- verifies the resulting APK signature
- uploads the APK as an Actions artifact
- creates the versioned GitHub Release when that version tag does not already exist
- publishes the asset as exactly `Scribit.apk`

The button at the top of this README always targets:

```text
/releases/latest/download/Scribit.apk
```

### Permanent signing secrets

The private signing key is **not** stored in this repository.

The repository owner configures these Actions secrets:

- `SCRIBIT_KEYSTORE_BASE64`
- `SCRIBIT_KEYSTORE_PASSWORD`
- `SCRIBIT_KEY_ALIAS`
- `SCRIBIT_KEY_PASSWORD`

The same private keystore must be kept for the lifetime of this package ID:

```text
com.thechosenone.scribit
```

The permanent release certificate currently has this public SHA-256 fingerprint:

```text
04:B1:23:79:C7:4B:DF:26:DE:60:ED:75:3A:31:F9:3A:C4:31:EA:C0:E8:2C:E8:7D:48:AA:7E:5F:47:DE:05:F4
```

Losing that key means a differently signed APK cannot update phones that already have the signed Scribit release installed.

### Android Studio

For development builds:

- Clone the repository.
- Open it in a recent Android Studio.
- Let Gradle sync.
- Install Android SDK 36 if prompted.
- Build/run the `debug` variant normally.

For a locally signed `release` build, provide the same four signing values as environment variables before running `assembleRelease`.

## Versioning

The app version lives in `gradle.properties`:

```properties
scribit.versionName=1.2.0
scribit.versionCode=3
```

For each public release:

- bump `scribit.versionName`
- increment `scribit.versionCode`
- push to `main`

GitHub Actions publishes the release as `v<versionName>`. If that release already exists, the workflow keeps the existing release untouched and only uploads the new Actions artifact; bump the version before publishing another APK release.

## Current limitations

- PDF understanding is visual and uses the first few rendered pages rather than a full document text-extraction pipeline.
- Smart Search is AI-assisted query planning over local metadata/full-text search; it is not an embeddings database yet.
- There is no automatic cross-device sync.
- Backup is manual rather than scheduled/cloud-synced.
- Scribit is distributed as an APK from GitHub rather than through Google Play.

## Why “Scribit”?

Mostly *scribble + bit*.

The files arrive messy. Scribit keeps the useful bits. The purple squiggle is allowed to look slightly handwritten because that is kind of the whole point.
