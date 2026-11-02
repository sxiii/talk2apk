
package dev.talk2apk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FileChange(
    val path: String,
    val action: String,
    val content: String = ""
)

data class ChangeSummary(
    val path: String,
    val action: String,
    val added: Int,
    val removed: Int
)

class ProjectStore(context: Context, projectName: String) {
    val root: File = File(
        File(context.filesDir, "projects"),
        safeProjectName(projectName)
    ).apply { mkdirs() }

    private val stateFile = File(root, ".talk2apk-state.json")

    companion object {
        private const val DEFAULT_AI_CONTEXT_CHARS = 6_000
        private const val MAX_AI_FILE_SIZE = 128 * 1024L
        private const val MAX_AI_FILE_CHARS = 2_200
        private const val MAX_AI_TREE_CHARS = 1_400

        private val excludedDirectories = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".kotlin",
            ".cache",
            "build",
            "out",
            "node_modules",
            "vendor",
            "captures",
            ".cxx",
            ".externalNativeBuild",
            "target",
            "dist"
        )

        private val allowedExtensions = setOf(
            "kt", "kts", "java",
            "xml", "gradle", "properties",
            "toml", "json", "yaml", "yml",
            "md", "txt", "pro",
            "sh", "bash", "bat",
            "gitignore", "editorconfig",
            "html", "css", "js", "ts",
            "tsx", "jsx", "sql",
            "c", "h", "cpp", "hpp",
            "cmake", "makefile"
        )

        private val priorityPatterns = listOf(
            Regex("""(^|/)(settings\.gradle(\.kts)?|build\.gradle(\.kts)?|gradle\.properties|libs\.versions\.toml)$""") to 0,
            Regex("""(^|/)gradlew(\.bat)?$""") to 1,
            Regex("""(^|/)AndroidManifest\.xml$""") to 2,
            Regex("""(^|/)app/src/main/""") to 3,
            Regex("""(^|/)src/main/""") to 4,
            Regex("""(^|/)\.github/workflows/""") to 5,
            Regex("""(^|/)README(\.[^/]*)?$""", RegexOption.IGNORE_CASE) to 6,
            Regex("""(^|/)app/""") to 7,
            Regex("""(^|/)gradle/""") to 8
        )

        fun safeProjectName(name: String): String {
            val cleaned = name.trim()
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-', '.')

            return cleaned.ifBlank { "my-android-app" }.take(60)
        }
    }

    fun listFiles(): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it != stateFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it.startsWith(".git/") }
            .sorted()
            .toList()

    fun read(path: String): String {
        val file = resolve(path)
        return if (file.isFile) file.readText(Charsets.UTF_8) else ""
    }

    fun savePending(changes: List<FileChange>) {
        val array = JSONArray()

        changes.forEach { change ->
            array.put(
                JSONObject()
                    .put("path", change.path)
                    .put("action", change.action)
                    .put("content", change.content)
            )
        }

        stateFile.writeText(
            JSONObject().put("pending", array).toString(),
            Charsets.UTF_8
        )
    }

    fun loadPending(): List<FileChange> = try {
        val array = JSONObject(stateFile.readText(Charsets.UTF_8))
            .optJSONArray("pending") ?: JSONArray()

        (0 until array.length()).map { index ->
            array.getJSONObject(index).let {
                FileChange(
                    it.getString("path"),
                    it.getString("action"),
                    it.optString("content", "")
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun clearPending() {
        if (stateFile.exists()) stateFile.delete()
    }

    fun apply(changes: List<FileChange>) {
        changes.forEach { change ->
            val file = resolve(change.path)

            when (change.action.lowercase()) {
                "write", "create", "update" -> {
                    file.parentFile?.mkdirs()
                    file.writeText(change.content, Charsets.UTF_8)
                }

                "delete", "remove" -> {
                    if (file.exists()) file.delete()

                    var parent = file.parentFile
                    while (
                        parent != null &&
                        parent != root &&
                        parent.isDirectory &&
                        parent.list()?.isEmpty() == true
                    ) {
                        parent.delete()
                        parent = parent.parentFile
                    }
                }

                else -> throw IllegalArgumentException(
                    "Unsupported file action: ${change.action}"
                )
            }
        }
    }

    fun summaries(changes: List<FileChange>): List<ChangeSummary> =
        changes.map { change ->
            val old = read(change.path).lines().let {
                if (it.size == 1 && it[0].isEmpty()) emptyList() else it
            }

            val new = if (
                change.action.equals("delete", true) ||
                change.action.equals("remove", true)
            ) {
                emptyList()
            } else {
                change.content.lines().let {
                    if (it.size == 1 && it[0].isEmpty()) emptyList() else it
                }
            }

            var prefix = 0
            while (
                prefix < old.size &&
                prefix < new.size &&
                old[prefix] == new[prefix]
            ) {
                prefix++
            }

            var suffix = 0
            while (
                suffix < old.size - prefix &&
                suffix < new.size - prefix &&
                old[old.lastIndex - suffix] == new[new.lastIndex - suffix]
            ) {
                suffix++
            }

            ChangeSummary(
                change.path,
                change.action,
                new.size - prefix - suffix,
                old.size - prefix - suffix
            )
        }

    /**
     * Builds a compact, useful project snapshot for the AI.
     *
     * Generated directories, caches, binary files and oversized files
     * are excluded. Important Android and Gradle files are included first.
     */
    fun contextForAi(
        maxChars: Int = DEFAULT_AI_CONTEXT_CHARS
    ): String {
        val budget = maxChars.coerceAtLeast(500)
        val paths = listFiles()
            .filter(::isUsefulAiFile)
            .sortedWith(
                compareBy<String> { priorityOf(it) }
                    .thenBy { it.length }
                    .thenBy { it }
            )

        val tree = StringBuilder("PROJECT FILE TREE:\n")
        var treeRemaining = MAX_AI_TREE_CHARS

        for (path in paths) {
            val line = "$path\n"
            if (line.length > treeRemaining) break

            tree.append(line)
            treeRemaining -= line.length
        }

        if (treeRemaining <= 0 || tree.length >= MAX_AI_TREE_CHARS) {
            tree.append("... file tree shortened\n")
        }

        val header = tree.toString() +
            "\nCURRENT IMPORTANT FILE CONTENTS:\n"

        if (header.length >= budget) {
            return header.take(budget)
        }

        val output = StringBuilder(header)
        var remaining = budget - output.length

        for (path in paths) {
            if (remaining < 100) break

            val file = File(root, path)
            if (!file.isFile || file.length() > MAX_AI_FILE_SIZE) continue

            val content = try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }

            // Skip files that appear to contain binary data.
            if (content.contains('\u0000')) continue

            val fileLimit = minOf(
                MAX_AI_FILE_CHARS,
                remaining - path.length - 20
            )

            if (fileLimit <= 0) break

            val truncated = content.length > fileLimit
            val fileContent = if (truncated) {
                content.take(fileLimit) +
                    "\n... [file content truncated]"
            } else {
                content
            }

            val block = "\n--- FILE: $path ---\n$fileContent\n"

            if (block.length > remaining) {
                val shortened = block.take(remaining)
                output.append(shortened)
                remaining -= shortened.length
                break
            }

            output.append(block)
            remaining -= block.length
        }

        return output.toString().take(budget)
    }

    private fun isUsefulAiFile(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val segments = normalized.split('/')

        if (segments.any { it.lowercase() in excludedDirectories }) {
            return false
        }

        if (normalized == ".talk2apk-state.json") return false

        val fileName = segments.lastOrNull().orEmpty()
        val extension = fileName.substringAfterLast(
            '.',
            fileName
        ).lowercase()

        val hasKnownExtension = extension in allowedExtensions
        val isSpecialTextFile = fileName in setOf(
            "Dockerfile",
            "Makefile",
            "CMakeLists.txt",
            ".gitignore",
            ".gitattributes",
            ".editorconfig"
        )

        if (!hasKnownExtension && !isSpecialTextFile) return false

        val file = File(root, normalized)
        if (!file.isFile || file.length() > MAX_AI_FILE_SIZE) return false

        return true
    }

    private fun priorityOf(path: String): Int {
        return priorityPatterns.firstOrNull { (pattern, _) ->
            pattern.containsMatchIn(path)
        }?.second ?: 10
    }

    fun validatePath(path: String) {
        resolve(path)
    }

    private fun resolve(path: String): File {
        require(path.isNotBlank()) { "Empty file path" }
        require(!path.startsWith("/") && !path.startsWith("\\")) {
            "Absolute paths are not allowed"
        }
        require(!path.contains('\\')) {
            "Use forward slashes in file paths"
        }

        val parts = path.split('/')

        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe path: $path"
        }
        require(parts.none { it.equals(".git", true) }) {
            "AI cannot modify .git internals"
        }
        require(path != ".talk2apk-state.json") {
            "AI cannot modify Talk2APK internal state"
        }

        val target = File(root, path).canonicalFile

        require(target.toPath().startsWith(root.canonicalFile.toPath())) {
            "Path escapes project directory"
        }

        return target
    }
}

object AiFileProtocol {
    val systemPrompt = """
        You are Talk2APK, an expert Android coding agent. Use the supplied project tree; preserve unrelated files and make complete, minimal changes. Return changes via apply_file_changes tool calls, or exactly one JSON object: {"operation":"apply_changes","files":[{"path":"relative/path","action":"write|delete","content":"complete file contents"}]}. Never return snippets/diffs. Validate project-relative paths; never access secrets or write outside the project.
        CI RULE: The workflow MUST include `on: push` for the configured branch so a GitHub push starts the build automatically. Preserve existing workflow steps, add the push trigger if missing, and never require/add an in-app manual “Start Build” button. The app may use `[skip ci]` when the project's Auto-build setting is OFF. Preserve artifact name `talk2apk-debug-apk` and upload the APK with actions/upload-artifact.
        VERSIONS (mandatory): Whenever creating/editing Android, Gradle or CI files, verify and use the newest stable, mutually compatible releases available at generation time for EVERY used GitHub Action (especially checkout, setup-java, setup-android, setup-gradle, upload-artifact) and all Android/Gradle dependencies (Gradle, AGP, Kotlin/Compose compiler, SDK, Compose BOM, AndroidX). Check official release/docs, not memory; avoid deprecated runtimes and previews.
        If live verification is unavailable, state that clearly and do not claim versions are current. Never downgrade an action or dependency without a compatibility reason.
    """.trimIndent()

    fun toolDefinition(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "apply_file_changes")
                    .put(
                        "description",
                        "Create, update or delete project source files. Provide complete file contents for write operations."
                    )
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "files",
                                    JSONObject()
                                        .put("type", "array")
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "object")
                                                .put(
                                                    "properties",
                                                    JSONObject()
                                                        .put(
                                                            "path",
                                                            JSONObject().put("type", "string")
                                                        )
                                                        .put(
                                                            "action",
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put(
                                                                    "enum",
                                                                    JSONArray()
                                                                        .put("write")
                                                                        .put("delete")
                                                                )
                                                        )
                                                        .put(
                                                            "content",
                                                            JSONObject().put("type", "string")
                                                        )
                                                )
                                                .put(
                                                    "required",
                                                    JSONArray()
                                                        .put("path")
                                                        .put("action")
                                                )
                                        )
                                )
                            )
                            .put("required", JSONArray().put("files"))
                            .put("additionalProperties", false)
                    )
            )

    fun parseFiles(json: JSONObject): List<FileChange> {
        val array = json.optJSONArray("files")
            ?: throw IllegalArgumentException("AI response has no files array")

        require(array.length() in 1..100) {
            "Expected between 1 and 100 file operations"
        }

        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val path = item.getString("path")
            val action = item.optString("action", "write").lowercase()

            require(
                action in setOf(
                    "write", "delete", "create", "update", "remove"
                )
            ) {
                "Unsupported action: $action"
            }

            val content = item.optString("content", "")

            require(content.length <= 1_500_000) {
                "File is too large: $path"
            }

            FileChange(path, action, content)
        }
    }

    fun parseFallback(content: String): List<FileChange>? {
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        if (start < 0 || end <= start) return null

        return try {
            val obj = JSONObject(cleaned.substring(start, end + 1))

            if (
                obj.optString("operation") != "apply_changes" &&
                !obj.has("files")
            ) {
                null
            } else {
                parseFiles(obj)
            }
        } catch (_: Exception) {
            null
        }
    }
}

object DefaultBuildWorkflow {
    const val path = ".github/workflows/android-build.yml"

    fun ensurePushTrigger(existing: String): String {
        if (Regex("(?m)^\\s*push\\s*:").containsMatchIn(existing)) {
            return existing
        }

        val onLine = Regex("(?m)^on\\s*:\\s*(?:#.*)?$").find(existing)
            ?: return content

        val insertAt = onLine.range.last + 1

        return existing.substring(0, insertAt) +
            "\n  push:\n    branches:\n      - \"**\"" +
            existing.substring(insertAt)
    }

    val content = """
        name: Android Build

        on:
          push:
            branches:
              - "**"
          workflow_dispatch:

        permissions:
          contents: read

        concurrency:
          group: talk2apk-android-${'$'}{{ github.ref }}
          cancel-in-progress: true

        jobs:
          build:
            runs-on: ubuntu-latest
            timeout-minutes: 30
            steps:
              - name: Checkout
                uses: actions/checkout@v7
                with:
                  persist-credentials: false
              - name: Set up JDK 17
                uses: actions/setup-java@v6
                with:
                  distribution: temurin
                  java-version: '17'

              - name: Set up Android SDK
                uses: android-actions/setup-android@v4

              - name: Set up Gradle
                uses: gradle/actions/setup-gradle@v6
                with:
                  gradle-version: '9.8.2'
                  cache-provider: basic

              - name: Build debug APK
                run: gradle --no-daemon --stacktrace assembleDebug
              - name: Upload APK artifact
                uses: actions/upload-artifact@v7
                with:
                  name: talk2apk-debug-apk
                  path: app/build/outputs/apk/debug/app-debug.apk
                  if-no-files-found: error
                  retention-days: 7
    """.trimIndent()
}

object ProjectDirectories {
    fun rename(
        context: android.content.Context,
        oldName: String,
        newName: String
    ) {
        val base = File(context.filesDir, "projects").apply { mkdirs() }
        val source = File(base, ProjectStore.safeProjectName(oldName))
        val target = File(base, ProjectStore.safeProjectName(newName))

        if (source.canonicalFile == target.canonicalFile) return

        require(!target.exists()) {
            "A project folder with that name already exists"
        }

        if (source.exists()) {
            require(source.renameTo(target)) {
                "Could not rename the local project folder"
            }
        }
    }

    fun delete(
        context: android.content.Context,
        projectName: String
    ): Boolean {
        val directory = File(
            File(context.filesDir, "projects"),
            ProjectStore.safeProjectName(projectName)
        )

        return !directory.exists() || directory.deleteRecursively()
    }

    fun clone(
        context: android.content.Context,
        sourceName: String,
        targetName: String
    ) {
        val base = File(context.filesDir, "projects")
        val source = File(base, ProjectStore.safeProjectName(sourceName))
        val target = File(base, ProjectStore.safeProjectName(targetName))

        require(source.canonicalFile != target.canonicalFile) {
            "A project cannot be cloned onto itself"
        }
        require(!target.exists() || target.list()?.isEmpty() == true) {
            "A project with that name already exists"
        }

        if (source.exists()) {
            source.copyRecursively(target, overwrite = true)
        } else {
            target.mkdirs()
        }
    }
}
