<p align="center">
  <img src="docs/scribit-logo.svg" alt="Scribit" width="620" />
</p>

<p align="center">
  A private, phone-first document archive for Android.<br/>
  Import the ugly filenames. Let Scribit make sense of them.
</p>

<p align="center">
  <a href="https://github.com/The-Chosen-One-O5/Scribit/releases/latest/download/Scribit.apk">
    <img alt="Install Scribit" src="https://img.shields.io/badge/Install-Scribit.apk-6558E8?style=for-the-badge&logo=android&logoColor=white" />
  </a>
  &nbsp;
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
</p>

## What is Scribit?

My phone has always had the same problem: the important documents are technically *there*, but they are buried under filenames like `IMG_8392.jpg`, `scan.pdf`, and whatever WhatsApp decided to call a file that day.

Scribit is my attempt at fixing that without turning the phone into a tiny server.

You share or import a document, Scribit keeps a private archive copy, and an OpenAI-compatible model can work out what the file actually is: a marksheet, passport, permit, certificate, bill, letter, etc. The useful metadata is stored locally so you can find the file later without remembering the exact filename.

The original file is never renamed, moved, or deleted by the AI.

## What it can do

- Import **PDFs, images, and text files** from Android's file picker.
- Receive files directly through **Share → Scribit** from other Android apps.
- Take a quick photo/scan using the system camera.
- Classify documents with your own **OpenAI-compatible API**.
- Extract useful details such as:
  - title
  - category and document type
  - organisation/institution
  - issue and expiry dates
  - tags
  - summary
  - searchable terms
- Keep a fast **local SQLite + full-text search index**.
- Use **Smart Search** for vague searches such as `that uni admission letter`.
- Flag uncertain classifications as **Needs Review** instead of pretending the AI is always right.
- Let you manually edit metadata or re-run classification.
- Notify you about documents that are getting close to their expiry date.
- Follow the phone theme, or stay permanently in **Light** or **Dark** mode.

## Install

### Easiest way

- Tap the **Download Scribit APK** button near the top of this README.
- Download `Scribit.apk` from the GitHub Release.
- Open the downloaded APK on your Android phone.
- Android may ask you to allow installs from your browser/files app. Allow it for that app, then continue.
- Install Scribit and open it.

> The GitHub build is currently a debug-signed APK. It is installable, but Android may show the usual warning for apps installed outside the Play Store.

### First launch

Scribit asks for four things:

- **API base URL** — for example `https://your-provider.example/v1`
- **API key**
- **Model name**
- **Vision support** — enable this when the model can understand images

Use **Test** before saving if you want to make sure the provider is reachable.

Your API key is encrypted using Android Keystore before it is stored on the phone.

## Daily use

There are three easy ways to put something into Scribit:

- **Share it:** open a PDF/image in WhatsApp, Files, your browser, etc. → Share → Scribit.
- **Import it:** open Scribit → Add document.
- **Scan it:** tap the camera button and take a picture.

After that, the app queues the classification work with Android WorkManager. It does **not** keep a terminal, Python process, polling loop, or permanent background service alive.

When classification finishes, the document appears in the library. If the model is unsure, it goes into **Needs Review** so you can correct it.

## Privacy / data behaviour

A few rules are intentionally boring because documents can be important:

- Scribit never asks the AI to move, rename, overwrite, or delete your original file.
- An imported copy lives inside Scribit's private app storage.
- The API key is encrypted at rest with **Android Keystore + AES-GCM**.
- Document content is sent to your configured API only when AI processing is needed.
- Search/index data stays in the local SQLite database.
- There is no Scribit cloud account and no built-in cloud sync right now.
- AI output is treated as metadata, not truth. Low-confidence results are reviewable.

## Building it yourself

You can build the app locally with Android Studio, but the repo is also set up so GitHub can do it for you.

### GitHub Actions

Every push to `main` runs **Build & Publish Scribit APK**.

The workflow:

- checks out the project
- installs Java 17
- installs the Android SDK/build tools
- runs the Android debug build
- uploads the APK as an Actions artifact
- creates a fresh GitHub Release and marks it as the latest build
- publishes the APK as `Scribit.apk`

That last step is what powers the **Download Scribit APK** button at the top of this page.

### Android Studio

- Clone this repository.
- Open the project folder in a recent Android Studio.
- Let Gradle sync.
- Install Android SDK 36 if Android Studio asks for it.
- Build the `app` debug variant.
- The APK will be under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The repository contains `gradle-wrapper.properties`, but not `gradle-wrapper.jar`. The GitHub workflow does not need the wrapper JAR because it installs the requested Gradle version directly.

## API compatibility

Scribit targets OpenAI-style `POST /v1/chat/completions` APIs.

- If your base URL ends in `/v1`, Scribit adds `/chat/completions`.
- If you enter the full `/chat/completions` URL, Scribit uses it as-is.
- Image/PDF classification expects the provider to understand the common `image_url` message format with base64 data URLs.
- If your provider is text-only, disable Vision support. Text files can still be classified, while image/PDF metadata can be filled in manually.

## Current limitations

- PDFs are classified visually from the first few rendered pages rather than through a full PDF text-extraction engine.
- Smart Search currently plans a query over local metadata/full-text search; it is not a vector embedding database yet.
- There is no cross-device sync or automatic backup yet.
- The GitHub APK is a debug build, not a Play Store release build.

## Why the name?

**Scribit** is basically *scribble + bit*: messy documents in, useful little bits of information out.

And yes, the purple squiggle in the logo is supposed to look a little handwritten. That is the point. :)
