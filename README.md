# Talk2APK — Phase 1

A proof-of-concept Android client that triggers a GitHub Actions Android build, watches the workflow, and downloads the resulting APK artifact.

## What this phase proves

- Android app can authenticate to GitHub using a user-supplied fine-grained Personal Access Token.
- It dispatches a fixed workflow in a repository the user controls.
- It polls workflow runs until the run completes.
- It downloads the ZIP artifact and extracts the APK into the app's cache.
- The project includes a GitHub Actions workflow that builds this Android app itself.

This is deliberately a Phase 1 transport/build proof, not yet the conversational AI generator or a production credential architecture.

## Quick start

1. Create a GitHub repository by uploading the contents of this ZIP to the repository root. Do not upload the outer `talk2apk-phase1` folder itself.
2. Ensure the default branch is `main`.
3. Wait for the first Actions run to finish, or manually run **Actions → Talk2APK Android Build → Run workflow**.
4. Install the generated `talk2apk-debug-apk` artifact's APK if you want to test from a clean start.
5. Open Talk2APK and enter:
   - GitHub owner: your username or organization
   - Repository: the repository name
   - Branch: `main` (or the branch containing the workflow)
   - Workflow file: `android-build.yml`
   - A fine-grained token as described below
6. Tap **Build APK**. The app triggers the workflow, waits for completion, and downloads the APK.

## Token setup

Create a fine-grained token at GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens.

Limit it to the single repository used for the test. Grant:
- **Actions: Read and write**
- **Contents: Read-only**
- **Metadata: Read-only** (usually selected automatically)

The app uses the token only for GitHub API requests. Phase 1 keeps the token in memory only; it is not saved to disk. Avoid pasting a token into screenshots, issue reports, or logs. Revoke the token after testing if you no longer need it.

## Build workflow requirements

The workflow must exist on the repository's default branch for `workflow_dispatch` to work. The app requests a workflow dispatch against the configured branch. The workflow builds the project at repository root and uploads `app/build/outputs/apk/debug/app-debug.apk` as a GitHub Actions artifact.

## Build locally

Open this folder in Android Studio, or build on a machine with JDK 17, Android SDK 35, and Gradle 8.11.1 installed:

```bash
gradle assembleDebug
```

The GitHub Actions workflow uses a GitHub-hosted Ubuntu runner, installs JDK 17 and Android SDK, and runs the same Gradle build.

## Current limitations

- Phase 1 only builds the repository's existing Android project. It does not generate or edit app source from a conversation yet.
- The build runs while the app remains open; long-running background job recovery and notifications are not implemented.
- It downloads a debug APK, not a production release APK.
- GitHub Actions usage limits and artifact retention still apply.
- The app uses a manually created fine-grained token as a temporary MVP. Before public release, replace this with a GitHub App/OAuth-based authorization flow and carefully reviewed token handling.
- Do not use this prototype with repositories containing sensitive secrets or untrusted workflows. A build workflow can execute code from the repository.

## Troubleshooting

- **404 / workflow not found:** Check owner, repository, workflow filename, and that `android-build.yml` exists on the default branch.
- **401 / 403:** Token may be expired or missing Actions write permission, or it may not have access to that repository.
- **No run found:** Confirm Actions are enabled and the workflow has `workflow_dispatch`.
- **Build failed:** Open the repository's Actions tab and inspect the failed step.
- **No APK artifact:** Confirm the workflow uploaded `app-debug.apk` successfully.
