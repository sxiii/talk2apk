package dev.talk2apk

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.zip.ZipInputStream

data class BuildSnapshot(
    val runId: Long,
    val status: String,
    val conclusion: String = "",
    val htmlUrl: String = "",
    val artifactId: Long? = null,
    val message: String = "",
    val errorDetails: String = ""
) {
    val completed: Boolean get() = status == "completed"
    val succeeded: Boolean get() = completed && conclusion == "success" && artifactId != null
}

class GitHubBuildClient(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val workflow: String,
    private val cacheDir: File
) {
    private val api = "https://api.github.com"

    private fun connection(method: String, path: String, body: String? = null): HttpURLConnection {
        val c = (URL(api + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Talk2APK")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val error = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw IllegalStateException("GitHub API HTTP $code: ${error.take(900)}")
        }
        return c
    }

    private fun getJson(path: String): JSONObject {
        val c = connection("GET", path)
        return try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }

    fun dispatchAndFindRun(onStatus: (String) -> Unit): Pair<Long, String> {
        validate()
        val encodedWorkflow = enc(workflow)
        val encodedBranch = enc(branch)
        val started = Instant.now()
        onStatus("Starting GitHub Actions…")
        connection("POST", "/repos/$owner/$repo/actions/workflows/$encodedWorkflow/dispatches",
            JSONObject().put("ref", branch).toString()).disconnect()

        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val runs = getJson("/repos/$owner/$repo/actions/runs?event=workflow_dispatch&branch=$encodedBranch&per_page=30")
                .optJSONArray("workflow_runs") ?: JSONArray()
            for (i in 0 until runs.length()) {
                val r = runs.getJSONObject(i)
                val pathMatches = r.optString("path").endsWith(workflow) || workflow.matches(Regex("\\d+"))
                val created = runInstant(r.optString("created_at"))
                if (pathMatches && !created.isBefore(started.minusSeconds(10))) {
                    return r.getLong("id") to r.optString("html_url")
                }
            }
            onStatus("Workflow dispatched. Waiting for GitHub to create the run…")
            Thread.sleep(2500)
        }
        throw IllegalStateException("GitHub accepted the workflow dispatch but no run appeared within 90 seconds. Check workflow_dispatch, branch and Actions permissions.")
    }

    fun getSnapshot(runId: Long): BuildSnapshot {
        val run = getJson("/repos/$owner/$repo/actions/runs/$runId")
        val status = run.optString("status", "queued")
        val conclusion = run.optString("conclusion")
        val htmlUrl = run.optString("html_url")
        if (status != "completed") {
            val message = when (status) {
                "queued", "requested", "waiting", "pending" -> "Build queued — waiting for a GitHub runner…"
                "in_progress" -> "GitHub Actions is building your project…"
                else -> "GitHub Actions status: $status"
            }
            return BuildSnapshot(runId, status, conclusion, htmlUrl, message = message)
        }
        if (conclusion == "success") {
            val artifactId = findArtifact(runId)
            return BuildSnapshot(runId, status, conclusion, htmlUrl, artifactId,
                if (artifactId != null) "Build successful. APK artifact is ready to download." else "Build succeeded, but the APK artifact has not appeared yet.")
        }
        val details = collectFailureDetails(runId)
        return BuildSnapshot(runId, status, conclusion, htmlUrl, message = "Build finished: ${conclusion.ifBlank { "unknown result" }}.", errorDetails = details)
    }

    private fun findArtifact(runId: Long): Long? {
        val artifacts = getJson("/repos/$owner/$repo/actions/runs/$runId/artifacts?per_page=100")
            .optJSONArray("artifacts") ?: return null
        for (i in 0 until artifacts.length()) {
            val a = artifacts.getJSONObject(i)
            if (a.optString("name") == "talk2apk-debug-apk" && !a.optBoolean("expired")) {
                return a.optLong("id").takeIf { it > 0 }
            }
        }
        return null
    }

    private fun collectFailureDetails(runId: Long): String {
        val summary = StringBuilder()
        try {
            val jobs = getJson("/repos/$owner/$repo/actions/runs/$runId/jobs?per_page=100").optJSONArray("jobs") ?: JSONArray()
            for (i in 0 until jobs.length()) {
                val job = jobs.getJSONObject(i)
                if (job.optString("conclusion") == "failure") {
                    summary.append("Failed job: ").append(job.optString("name")).append('\n')
                    val steps = job.optJSONArray("steps") ?: JSONArray()
                    for (j in 0 until steps.length()) {
                        val step = steps.getJSONObject(j)
                        if (step.optString("conclusion") == "failure") {
                            summary.append("Failed step: ").append(step.optString("name")).append('\n')
                        }
                    }
                }
            }
        } catch (e: Exception) { summary.append("Could not retrieve job details: ${e.message}\n") }
        val logErrors = try { downloadLogErrors(runId) } catch (e: Exception) { "" }
        if (logErrors.isNotBlank()) summary.append("\nBuild log errors:\n").append(logErrors)
        if (summary.isBlank()) summary.append("GitHub reported a failed build but did not provide job or log details. Open the run URL to inspect it.")
        return summary.toString().trim().take(7000)
    }

    private fun downloadLogErrors(runId: Long): String {
        val c = (URL("$api/repos/$owner/$repo/actions/runs/$runId/logs").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Talk2APK")
        }
        val code = c.responseCode
        val location = c.getHeaderField("Location")
        val bytes = if (code in 300..399 && !location.isNullOrBlank()) {
            c.disconnect()
            val redirected = (URL(location).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000 }
            try { require(redirected.responseCode in 200..299) { "Log download HTTP ${redirected.responseCode}" }; redirected.inputStream.use { readBounded(it, 50 * 1024 * 1024) } }
            finally { redirected.disconnect() }
        } else {
            try { require(code in 200..299) { "Logs HTTP $code" }; c.inputStream.use { readBounded(it, 50 * 1024 * 1024) } }
            finally { c.disconnect() }
        }
        val candidates = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".txt", true)) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 5 * 1024 * 1024) break
                        out.write(buf, 0, n)
                    }
                    val lines = out.toString(Charsets.UTF_8.name()).lines()
                    val matches = lines.withIndex().filter { (_, line) ->
                        val l = line.lowercase()
                        listOf("error:", "e:", "failure:", "failed", "unresolved reference", "cannot infer", "could not resolve", "execution failed", "exception").any { l.contains(it) }
                    }.map { (idx, line) ->
                        val from = (idx - 1).coerceAtLeast(0)
                        val to = (idx + 1).coerceAtMost(lines.lastIndex)
                        lines.subList(from, to + 1).joinToString("\n")
                    }
                    if (matches.isNotEmpty()) candidates.add("[${entry.name}]\n" + matches.takeLast(18).joinToString("\n"))
                }
            }
        }
        return candidates.joinToString("\n\n").takeLast(6000)
    }

    fun downloadApk(artifactId: Long): File {
        val archive = File(cacheDir, "talk2apk-${artifactId}.artifact.zip")
        val c = connection("GET", "/repos/$owner/$repo/actions/artifacts/$artifactId/zip")
        try {
            c.inputStream.use { input -> FileOutputStream(archive).use { output -> copyBounded(input, output, 250L * 1024 * 1024) } }
        } finally { c.disconnect() }
        require(archive.exists() && archive.length() > 0) { "GitHub returned an empty artifact archive" }
        val apk = File(cacheDir, "talk2apk-${artifactId}.apk")
        var extracted = false
        var extractedBytes = 0L
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.substringAfterLast('/').endsWith(".apk", true)) continue
                    FileOutputStream(apk).use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = zis.read(buffer)
                            if (n < 0) break
                            extractedBytes += n
                            require(extractedBytes <= 250L * 1024 * 1024) { "APK artifact is too large" }
                            out.write(buffer, 0, n)
                        }
                    }
                    extracted = true
                    break
                }
            }
        } finally { archive.delete() }
        require(extracted && apk.exists() && apk.length() > 0) { "Downloaded GitHub artifact ZIP did not contain an APK" }
        return apk
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Downloaded artifact exceeds the $maxBytes byte safety limit" }
            output.write(buffer, 0, count)
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Downloaded archive exceeds the $maxBytes byte safety limit" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun validate() {
        require(token.isNotBlank()) { "GitHub token is required" }
        require(owner.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid GitHub owner" }
        require(repo.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid GitHub repository" }
        require(branch.isNotBlank() && !branch.contains("..")) { "Invalid branch" }
        require(workflow.endsWith(".yml") || workflow.endsWith(".yaml") || workflow.matches(Regex("\\d+"))) { "Workflow must be a YAML filename or workflow ID" }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun runInstant(value: String): Instant = try { Instant.parse(value) } catch (_: Exception) { Instant.EPOCH }
}
