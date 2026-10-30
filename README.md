# Talk2APK 0.2.0

Talk2APK is an Android client prototype for planning Android apps with an AI provider and triggering APK builds through GitHub Actions.

## What's new

- AI chat screen using an OpenAI-compatible Chat Completions adapter.
- Configurable HTTPS API base URL, model ID, and API key.
- API key encrypted at rest with AES-GCM and a non-exportable Android Keystore key.
- GitHub build screen retained from Phase 1.
- APK installation now checks Android's per-app "Install unknown apps" permission, opens the correct settings page, resumes installation after permission is granted, and uses `ACTION_INSTALL_PACKAGE` with a FileProvider URI.
- Updated Android build stack and GitHub Actions Node 24-compatible action releases.

## Important AI subscription note

This client accepts an API key for an OpenAI-compatible `/v1/chat/completions` endpoint. A ChatGPT, Claude, or Gemini consumer subscription is not automatically an API credential. API access and billing are governed by the configured provider.

The current AI adapter provides conversational app planning only. It does **not yet** generate project files, commit AI changes to GitHub, or initiate a build from chat. That is the next phase.

Supported endpoint shape:
- Base URL `https://api.example.com/v1`
- `POST /chat/completions`
- Bearer API key
- OpenAI-compatible JSON request/response schema

For providers with different native APIs, implement a dedicated adapter rather than pretending they are compatible.

## Build

The GitHub Actions workflow builds the debug APK on pushes to `main` and on manual dispatch.

Stack currently configured:
- Android Gradle Plugin 9.5.0
- Kotlin Compose compiler plugin 2.4.21 (AGP 9 built-in Kotlin; separate `org.jetbrains.kotlin.android` plugin removed)
- Gradle 9.8.2
- JDK 17
- Compile SDK 37 / target SDK 36
- Compose BOM 2026.09.00
- AndroidX Core 1.19.1
- Activity Compose 1.13.0
- Lifecycle Runtime 2.11.0

The workflow uses:
- `actions/checkout@v7`
- `actions/setup-java@v6`
- `android-actions/setup-android@v4`
- `gradle/actions/setup-gradle@v6`
- `actions/upload-artifact@v7`

These are Node 24-compatible releases. GitHub removed Node 20 from Actions runners on September 23, 2026.

## Setup

1. Upload the ZIP contents to the root of your GitHub repository, preserving `.github/workflows/android-build.yml`.
2. Ensure Actions are enabled and the default branch is `main`.
3. Open **Actions → Talk2APK Android Build** and wait for the first run.
4. Download the `talk2apk-debug-apk` artifact and install it.
5. In Talk2APK, configure an AI endpoint and API key under Settings.
6. In the Build tab, configure repository owner/name/branch/workflow and a fine-grained GitHub token.

GitHub fine-grained token permissions:
- Actions: Read and write
- Contents: Read-only
- Metadata: Read (normally implicit)

Restrict the token to the single repository. The token is held in memory only and cleared after the build attempt. This is still a prototype; replace PAT entry with a GitHub App authorization flow before broad distribution.

## APK installation

Android requires explicit per-app permission to install packages from unknown sources. The app checks `PackageManager.canRequestPackageInstalls()`, opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Talk2APK when necessary, and automatically retries installation when the user returns after granting permission.

If the settings screen was dismissed without granting permission, tap **Install APK** again and grant it. The APK is exposed only through the app's non-exported FileProvider with a temporary read grant.

## Security and limitations

- Only HTTPS AI endpoints are accepted. The API key is encrypted at rest using Android Keystore AES/GCM and is sent to the configured endpoint in an Authorization header.
- Do not configure an endpoint you do not trust; that endpoint receives your prompts and API key.
- GitHub token is not persisted.
- Build workflows execute repository code. Use a repository you control and do not grant access to unrelated private repositories.
- No streaming responses, conversation persistence, file attachments, or model tool calling yet.
- Chat does not currently edit project source. It is an app-planning chat only.
- Builds are foreground-only; background job recovery is not implemented.
- Debug APKs are returned; production release signing is not implemented.
- GitHub Actions minutes and artifact retention limits apply.

## Next milestone

Connect the AI conversation to a structured app specification and source-generation workflow:
1. Turn conversation into a versioned app specification.
2. Generate/update a constrained Android project template.
3. Validate generated files and reject unsafe build/workflow changes.
4. Commit source changes to the user's repository.
5. Dispatch the fixed trusted build workflow.
6. Return the APK and build diagnostics in the same conversation.
