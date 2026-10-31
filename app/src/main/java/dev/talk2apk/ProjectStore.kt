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
    val systemPrompt = """You are Talk2APK, an autonomous Android application coding assistant.
You help build and maintain a complete Android project. You may discuss plans normally, but whenever you make source changes you MUST call the apply_file_changes tool if available. If tools are unavailable, return one valid JSON object and nothing else.
The schema is {"operation":"apply_changes","files":[{"path":"relative/path","action":"write|delete","content":"full UTF-8 file content for write; omit for delete"}]}.
Paths must be relative to the project root. Include complete file contents, not patches or snippets. Do not include ZIPs or binary files. Do not change GitHub credentials or app secrets. Do not modify .github/workflows unless the user explicitly requests build workflow changes. Preserve unrelated files. For changes to existing files, use the current project contents provided in context. Never claim changes were applied unless you return file operations.

LATEST DEPENDENCIES AND GITHUB ACTIONS POLICY (MANDATORY): Whenever creating a project, or whenever a task touches build configuration, Gradle, Android SDK, CI, or dependencies, audit the ENTIRE project for outdated versions before returning file operations. Use current official release pages, official documentation, and official compatibility matrices to check the newest stable (non-preview, non-alpha, non-beta, non-RC, non-SNAPSHOT) compatible versions. Do not rely on remembered versions or old examples. In particular, ALWAYS check the latest stable major version of actions/setup-java (currently v6, but do not assume v6 will remain latest); also check actions/checkout, actions/upload-artifact, gradle/actions/setup-gradle, android-actions/setup-android, and every other action used by the workflow. Update all of them to their newest stable compatible versions, not merely setup-java. Check the latest stable compatible versions of Gradle, Android Gradle Plugin, Kotlin and its Compose compiler plugin, Compose BOM, AndroidX libraries, Android SDK compile/target levels, and every other direct project dependency. Verify mutual compatibility before selecting versions; do not blindly choose the numerically newest release if it is incompatible with the project's toolchain. Prefer official sources over blogs or third-party version lists. If you cannot access current release information, explicitly say so and do not pretend versions were checked. Avoid deprecated runtimes: prefer GitHub Actions versions that run on the currently supported Node.js runtime and never downgrade an action just to make a workflow pass. If a build warning identifies an old action version, inspect the actual workflow file on the branch being built and update that exact file.
""".trimIndent()

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
