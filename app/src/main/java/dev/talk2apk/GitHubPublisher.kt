package dev.talk2apk

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GitHubPublisher(private val token: String, private val owner: String, private val repo: String, private val branch: String) {
    private val api = "https://api.github.com"
    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = (URL(api + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Talk2APK")
            if (body != null) {
                doOutput = true; setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("GitHub API HTTP $code: ${raw.take(700)}")
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally { c.disconnect() }
    }

    fun syncProjectIfEmpty(store: ProjectStore): Int {
        if (store.listFiles().isNotEmpty()) return 0
        val encBranch = URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        val ref = try {
            call("GET", "/repos/$owner/$repo/git/ref/heads/$encBranch")
        } catch (e: Exception) {
            if (!e.message.orEmpty().contains("HTTP 404")) throw e
            val repository = call("GET", "/repos/$owner/$repo")
            val defaultBranch = repository.optString("default_branch")
            if (defaultBranch.isBlank()) return 0 // Newly-created, empty repository has no Git ref yet.
            call("GET", "/repos/$owner/$repo/git/ref/heads/${URLEncoder.encode(defaultBranch, "UTF-8").replace("+", "%20")}")
        }
        val commitSha = ref.getJSONObject("object").getString("sha")
        val commit = call("GET", "/repos/$owner/$repo/git/commits/$commitSha")
        val treeSha = commit.getJSONObject("tree").getString("sha")
        val tree = call("GET", "/repos/$owner/$repo/git/trees/$treeSha?recursive=1")
        require(!tree.optBoolean("truncated")) { "Repository is too large to synchronise in one request" }
        val entries = tree.optJSONArray("tree") ?: JSONArray()
        val changes = mutableListOf<FileChange>()
        val ignored = listOf(".git/", ".gradle/", "build/", ".idea/", "app/build/", "captures/")
        val binaryExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "ico", "pdf", "zip", "jar", "class", "so", "apk", "aab", "ttf", "otf", "mp3", "mp4", "wav", "webm")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.optString("type") != "blob") continue
            val path = entry.optString("path")
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ignored.any { path.startsWith(it) } || path == "local.properties" || ext in binaryExtensions) continue
            if (entry.optLong("size", 0L) > 1_500_000L) continue
            val sha = entry.optString("sha")
            val blob = call("GET", "/repos/$owner/$repo/git/blobs/$sha")
            if (blob.optString("encoding") != "base64") continue
            val raw = android.util.Base64.decode(blob.getString("content").replace("\n", ""), android.util.Base64.DEFAULT)
            val content = try { String(raw, Charsets.UTF_8) } catch (_: Exception) { continue }
            if (content.contains('\u0000')) continue
            changes.add(FileChange(path, "write", content))
            if (changes.size > 500) throw IllegalStateException("Repository contains more than 500 files; sync limit reached")
        }
        if (changes.isNotEmpty()) store.apply(changes)
        return changes.size
    }

    fun publish(changes: List<FileChange>, commitMessage: String = "Talk2APK: update Android project"): String {
        require(token.isNotBlank()) { "GitHub token is required" }
        require(owner.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid GitHub owner" }
        require(repo.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid repository" }
        require(branch.isNotBlank() && !branch.contains("..")) { "Invalid branch" }
        require(changes.isNotEmpty()) { "There are no changes to publish" }
        require(changes.map { it.path }.distinct().size == changes.size) { "AI returned duplicate paths; ask it to consolidate the file operations" }
        val encBranch = URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        var parentSha: String? = null
        var baseTree: String? = null
        var branchAlreadyExists = true
        val ref = try {
            call("GET", "/repos/$owner/$repo/git/ref/heads/$encBranch")
        } catch (e: Exception) {
            if (!e.message.orEmpty().contains("HTTP 404")) throw e
            branchAlreadyExists = false
            val repository = call("GET", "/repos/$owner/$repo")
            val defaultBranch = repository.optString("default_branch")
            if (defaultBranch.isNotBlank()) {
                val defaultRef = call("GET", "/repos/$owner/$repo/git/ref/heads/${URLEncoder.encode(defaultBranch, "UTF-8").replace("+", "%20")}")
                parentSha = defaultRef.getJSONObject("object").getString("sha")
                val parent = call("GET", "/repos/$owner/$repo/git/commits/$parentSha")
                baseTree = parent.getJSONObject("tree").getString("sha")
            }
            JSONObject()
        }
        if (branchAlreadyExists) {
            parentSha = ref.getJSONObject("object").getString("sha")
            val parent = call("GET", "/repos/$owner/$repo/git/commits/$parentSha")
            baseTree = parent.getJSONObject("tree").getString("sha")
        }
        val tree = JSONArray()
        changes.forEach { change ->
            val path = change.path
            require(path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') && !path.split('/').any { it == ".." || it == "." || it.isBlank() }) { "Unsafe path: $path" }
            require(!path.split('/').any { it.equals(".git", true) }) { "Cannot publish .git paths" }
            val deleting = change.action.equals("delete", true) || change.action.equals("remove", true)
            if (deleting) {
                tree.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL))
            } else {
                val blob = call("POST", "/repos/$owner/$repo/git/blobs", JSONObject()
                    .put("content", change.content).put("encoding", "utf-8"))
                tree.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blob.getString("sha")))
            }
        }
        val treeBody = JSONObject().put("tree", tree)
        if (baseTree != null) treeBody.put("base_tree", baseTree)
        val newTree = call("POST", "/repos/$owner/$repo/git/trees", treeBody)
        val parents = JSONArray()
        parentSha?.let { parents.put(it) }
        val commit = call("POST", "/repos/$owner/$repo/git/commits", JSONObject()
            .put("message", commitMessage).put("tree", newTree.getString("sha")).put("parents", parents))
        val commitSha = commit.getString("sha")
        if (branchAlreadyExists) {
            call("PATCH", "/repos/$owner/$repo/git/refs/heads/$encBranch", JSONObject().put("sha", commitSha).put("force", false))
        } else {
            call("POST", "/repos/$owner/$repo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", commitSha))
        }
        return commitSha
    }
}
