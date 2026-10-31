# Talk2APK v0.5.0

Talk2APK is an Android client for generating Android project source with an AI model, reviewing changes, publishing them to GitHub, and receiving the APK built by GitHub Actions.

## v0.5.0 changes

- Added a Talk2APK launcher/app-bar logo (`app/src/main/res/mipmap-nodpi/talk2apk_icon.png`).
- Added a **Repeat this prompt** action to every user message in chat. It resubmits that exact prompt using the currently selected AI engine.
- Added a per-project **Auto-build after publishing** setting, ON by default.
- Removed the in-app **Start GitHub Actions build** button. Publishing creates a commit; the app then waits for the push-triggered workflow run and monitors it automatically.
- The default workflow now runs on pushes to all branches and still supports manual dispatch from GitHub's own UI.
- With Auto-build OFF, the commit message includes `[skip ci]` so GitHub skips the workflow.
- Added automatic run discovery by commit SHA, live job-step progress, and a collapsible build history.
- Failed run details stay collapsed until opened. **Send error to AI** sends the failure text as a new prompt and stages a fix for review.
- APK download is enabled only when GitHub reports success and the expected artifact is available. The artifact ZIP is downloaded and the APK extracted locally.
- Compact, strict AI system instructions require the newest stable, mutually compatible GitHub Actions and Android dependencies, verified from official sources, and insist on push-triggered builds.

## Workflow

1. Configure global or project-specific AI settings and the project's GitHub repository/token.
2. Describe an app or change in Chat. Use **Repeat this prompt** to test another model with the same prompt.
3. Review the file list and added/removed line counts in Changes / Build.
4. Press **Publish to GitHub**.
5. With Auto-build ON, GitHub Actions starts automatically on the push. Talk2APK discovers the run by commit SHA and polls it until success or failure.
6. On success, download the APK. On failure, expand the error or send it to AI with one tap.

## GitHub requirements

Fine-grained token permissions for the target repository:

- Contents: Read and write
- Actions: Read and write (for listing workflow runs, jobs and artifacts)
- Metadata: Read (automatically included)

The workflow uses `actions/checkout@v7`, `actions/setup-java@v6`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v6`, and `actions/upload-artifact@v7`. These are the configured versions in this Talk2APK project; the AI prompt instructs generated projects to verify the newest stable compatible releases rather than blindly copying them.

**Important:** GitHub Actions push-triggered workflows run when a user PAT pushes the commit. Do not replace the user's PAT with `GITHUB_TOKEN` for the publishing operation; GitHub suppresses many recursive workflow triggers for commits made using `GITHUB_TOKEN`.

## Security and limitations

- API keys and GitHub tokens are encrypted on-device using Android Keystore-backed AES-GCM.
- Source files are stored in app-private storage. AI file paths are validated against traversal and `.git` writes.
- Auto-build OFF adds `[skip ci]` to the commit message. It still publishes the source commit, but GitHub Actions skips that commit.
- The current AI transport supports OpenAI-compatible Chat Completions with function calling and a structured JSON fallback. Native Anthropic Messages, OpenAI Responses/Agents and Gemini protocols are not implemented.
- Build progress is based on GitHub Actions run and job-step data. GitHub does not provide a precise percentage for every Gradle task; the UI shows indeterminate progress plus the current step when available.
- The Android app does not run Gradle locally. All builds happen on GitHub-hosted runners.

## Build

The repository workflow builds the debug APK on every push to any branch and uploads `talk2apk-debug-apk` as a GitHub Actions artifact. The app also retains `workflow_dispatch` for manual runs initiated directly in GitHub.

No full Gradle compilation was available in the packaging environment. Run the repository's GitHub Actions workflow after uploading this source to verify the Android build.


### v0.5.2 resource fix

The app icon is now also provided as a text-based Android vector drawable at `app/src/main/res/drawable/talk2apk_icon.xml`. The manifest and Compose toolbar both reference `@drawable/talk2apk_icon` / `R.drawable.talk2apk_icon`. This avoids relying on a PNG being included by a ZIP extraction or Git ignore configuration.
