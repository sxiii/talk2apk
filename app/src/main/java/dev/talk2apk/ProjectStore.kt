package dev.talk2apk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FileChange(val path: String, val action: String, val content: String = "")
data class ChangeSummary(val path: String, val action: String, val added: Int, val removed: Int)

class ProjectStore(context: Context, projectName: String) {
    val root: File = File(File(context.filesDir, "projects"), safeProjectName(projectName)).apply { mkdirs() }
    private val stateFile = File(root, ".talk2apk-state.json")

    fun listFiles(): List<String> = root.walkTopDown().filter { it.isFile && it != stateFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }.filterNot { it.startsWith(".git/") }
        .sorted().toList()

    fun read(path: String): String {
        val file = resolve(path)
        return if (file.isFile) file.readText(Charsets.UTF_8) else ""
    }

    fun savePending(changes: List<FileChange>) {
        val arr = JSONArray()
        changes.forEach { arr.put(JSONObject().put("path", it.path).put("action", it.action).put("content", it.content)) }
        stateFile.writeText(JSONObject().put("pending", arr).toString(), Charsets.UTF_8)
    }

    fun loadPending(): List<FileChange> = try {
        val arr = JSONObject(stateFile.readText(Charsets.UTF_8)).optJSONArray("pending") ?: JSONArray()
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { FileChange(it.getString("path"), it.getString("action"), it.optString("content", "")) } }
    } catch (_: Exception) { emptyList() }

    fun clearPending() { if (stateFile.exists()) stateFile.delete() }

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
                    while (parent != null && parent != root && parent.isDirectory && parent.list()?.isEmpty() == true) {
                        parent.delete(); parent = parent.parentFile
                    }
                }
                else -> throw IllegalArgumentException("Unsupported file action: ${change.action}")
            }
        }
    }

    fun summaries(changes: List<FileChange>): List<ChangeSummary> = changes.map { c ->
        val old = read(c.path).lines().let { if (it.size == 1 && it[0].isEmpty()) emptyList() else it }
        val new = if (c.action.equals("delete", true) || c.action.equals("remove", true)) emptyList()
                  else c.content.lines().let { if (it.size == 1 && it[0].isEmpty()) emptyList() else it }
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
        ChangeSummary(c.path, c.action, new.size - prefix - suffix, old.size - prefix - suffix)
    }

    fun contextForAi(maxChars: Int = 70_000): String {
        val paths = listFiles()
        val out = StringBuilder("PROJECT FILE TREE:\n")
        paths.forEach { out.append(it).append('\n') }
        out.append("\nCURRENT FILE CONTENTS (truncated to fit context):\n")
        var remaining = maxChars - out.length
        for (path in paths) {
            if (remaining <= 0) break
            val text = read(path)
            val block = "\n--- FILE: $path ---\n$text\n"
            val part = block.take(remaining)
            out.append(part); remaining -= part.length
        }
        return out.toString()
    }

    fun validatePath(path: String) { resolve(path) }

    private fun resolve(path: String): File {
        require(path.isNotBlank()) { "Empty file path" }
        require(!path.startsWith("/") && !path.startsWith("\\")) { "Absolute paths are not allowed" }
        require(!path.contains('\\')) { "Use forward slashes in file paths" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe path: $path" }
        require(parts.none { it.equals(".git", true) }) { "AI cannot modify .git internals" }
        require(path != ".talk2apk-state.json") { "AI cannot modify Talk2APK internal state" }
        val target = File(root, path).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath())) { "Path escapes project directory" }
        return target
    }

    companion object {
        fun safeProjectName(name: String): String {
            val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.')
            return cleaned.ifBlank { "my-android-app" }.take(60)
        }
    }
}

object AiFileProtocol {
    val systemPrompt = """You are Talk2APK, an expert Android coding agent. Use the supplied project tree; preserve unrelated files and make complete, minimal changes. Return changes via apply_file_changes tool calls, or exactly one JSON object: {"operation":"apply_changes","files":[{"path":"relative/path","action":"write|delete","content":"complete file contents"}]}. Never return snippets/diffs. Validate project-relative paths; never access secrets or write outside the project.

CI RULE: The workflow MUST include `on: push` for the configured branch so a GitHub push starts the build automatically. Preserve existing workflow steps, add the push trigger if missing, and never require/add an in-app manual “Start Build” button. The app may use `[skip ci]` when the project's Auto-build setting is OFF. Preserve artifact name `talk2apk-debug-apk` and upload the APK with actions/upload-artifact.

VERSIONS (mandatory): Whenever creating/editing Android, Gradle or CI files, verify and use the newest stable, mutually compatible releases available at generation time for EVERY used GitHub Action (especially checkout, setup-java, setup-android, setup-gradle, upload-artifact) and all Android/Gradle dependencies (Gradle, AGP, Kotlin/Compose compiler, SDK, Compose BOM, AndroidX). Check official release/docs, not memory; avoid deprecated runtimes and previews. If live verification is unavailable, state that clearly and do not claim versions are current. Never downgrade an action or dependency without a compatibility reason.""".trimIndent()

    fun toolDefinition(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "apply_file_changes")
        .put("description", "Create, update or delete project source files. Provide complete file contents for write operations.")
        .put("parameters", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("files", JSONObject()
                .put("type", "array").put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("path", JSONObject().put("type", "string"))
                        .put("action", JSONObject().put("type", "string").put("enum", JSONArray().put("write").put("delete")))
                        .put("content", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("path").put("action")))))
            .put("required", JSONArray().put("files"))
            .put("additionalProperties", false)))

    fun parseFiles(json: JSONObject): List<FileChange> {
        val arr = json.optJSONArray("files") ?: throw IllegalArgumentException("AI response has no files array")
        require(arr.length() in 1..100) { "Expected between 1 and 100 file operations" }
        return (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            val path = item.getString("path")
            val action = item.optString("action", "write").lowercase()
            require(action in setOf("write", "delete", "create", "update", "remove")) { "Unsupported action: $action" }
            val content = item.optString("content", "")
            require(content.length <= 1_500_000) { "File is too large: $path" }
            FileChange(path, action, content)
        }
    }

    fun parseFallback(content: String): List<FileChange>? {
        val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(cleaned.substring(start, end + 1))
            if (obj.optString("operation") != "apply_changes" && !obj.has("files")) null else parseFiles(obj)
        } catch (_: Exception) { null }
    }
}


object DefaultBuildWorkflow {
    const val path = ".github/workflows/android-build.yml"

    fun ensurePushTrigger(existing: String): String {
        if (Regex("(?m)^\\s*push\\s*:").containsMatchIn(existing)) return existing
        val onLine = Regex("(?m)^on\\s*:\\s*(?:#.*)?$").find(existing)
            ?: return content
        val insertAt = onLine.range.last + 1
        return existing.substring(0, insertAt) + "\n  push:\n    branches:\n      - \"**\"" + existing.substring(insertAt)
    }

    val content = """name: Android Build

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
"""
}

object ProjectDirectories {
    fun rename(context: android.content.Context, oldName: String, newName: String) {
        val base = File(context.filesDir, "projects").apply { mkdirs() }
        val source = File(base, ProjectStore.safeProjectName(oldName))
        val target = File(base, ProjectStore.safeProjectName(newName))
        if (source.canonicalFile == target.canonicalFile) return
        require(!target.exists()) { "A project folder with that name already exists" }
        if (source.exists()) require(source.renameTo(target)) { "Could not rename the local project folder" }
    }

    fun delete(context: android.content.Context, projectName: String): Boolean {
        val dir = File(File(context.filesDir, "projects"), ProjectStore.safeProjectName(projectName))
        return !dir.exists() || dir.deleteRecursively()
    }

    fun clone(context: android.content.Context, sourceName: String, targetName: String) {
        val base = File(context.filesDir, "projects")
        val source = File(base, ProjectStore.safeProjectName(sourceName))
        val target = File(base, ProjectStore.safeProjectName(targetName))
        require(source.canonicalFile != target.canonicalFile) { "A project cannot be cloned onto itself" }
        require(!target.exists() || target.list()?.isEmpty() == true) { "A project with that name already exists" }
        if (source.exists()) source.copyRecursively(target, overwrite = true) else target.mkdirs()
    }
}
