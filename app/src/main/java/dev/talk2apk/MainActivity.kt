package dev.talk2apk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Talk2ApkScreen(
                        onInstall = { file ->
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this,
                                "${packageName}.provider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        },
                        cacheDir = cacheDir
                    )
                }
            }
        }
    }
}

@Composable
private fun Talk2ApkScreen(
    onInstall: (File) -> Unit,
    cacheDir: File
) {
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var workflow by remember { mutableStateOf("android-build.yml") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Connect a GitHub repository to build an APK.") }
    var busy by remember { mutableStateOf(false) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Talk2APK", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Phase 1 · GitHub Actions build tester",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "This prototype builds the Android project already in your repository. AI-driven source generation comes in a later phase.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = owner, onValueChange = { owner = it.trim() },
            label = { Text("GitHub owner / organization") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = repo, onValueChange = { repo = it.trim() },
            label = { Text("Repository name") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = branch, onValueChange = { branch = it.trim() },
            label = { Text("Branch") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = workflow, onValueChange = { workflow = it.trim() },
            label = { Text("Workflow filename") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("Fine-grained GitHub token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Token permissions: Actions read/write, Contents read-only, Metadata read. Limit it to this repository. The token is kept in memory only and cleared when you close the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    apkFile = null
                    status = "Starting build…"
                    try {
                        val result = withContext(Dispatchers.IO) {
                            GitHubBuildClient(token.trim(), owner.trim(), repo.trim(), branch.trim(), workflow.trim(), cacheDir)
                                .buildAndDownload { message ->
                                    // Dispatch status updates onto the Compose/UI coroutine.
                                    scope.launch { status = message }
                                }
                        }
                        apkFile = result
                        status = "Build successful. APK downloaded to the app's temporary cache."
                    } catch (e: Exception) {
                        status = "Build failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        busy = false
                        token = ""
                    }
                }
            },
            enabled = !busy && owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank() &&
                workflow.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text("Building…")
            } else {
                Text("Build APK")
            }
        }

        if (busy) {
            Text("Keep this screen open while the workflow runs. This first version does not yet resume jobs after the app is closed.")
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(status, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }

        apkFile?.let { file ->
            Text("Artifact: ${file.name}")
            Button(onClick = { onInstall(file) }, modifier = Modifier.fillMaxWidth()) {
                Text("Install APK")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Security note: use a fine-grained token scoped to one test repository. This prototype calls GitHub directly from your phone and is not yet a production OAuth integration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private class GitHubBuildClient(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val workflow: String,
    private val cacheDir: File
) {
    private val api = "https://api.github.com"
    private val userAgent = "Talk2APK-Phase1"

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        accept: String = "application/vnd.github+json"
    ): HttpURLConnection {
        val connection = (URL(api + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", userAgent)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            val stream = connection.errorStream
            val message = stream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            connection.disconnect()
            throw IllegalStateException("GitHub API HTTP $code: ${message.take(500)}")
        }
        return connection
    }

    fun buildAndDownload(onStatus: (String) -> Unit): File {
        require(owner.matches(Regex("[A-Za-z0-9-]+"))) { "Enter a valid GitHub owner." }
        require(repo.matches(Regex("[A-Za-z0-9._-]+"))) { "Enter a valid repository name." }
        require(branch.isNotBlank()) { "Branch cannot be empty." }
        require(workflow.endsWith(".yml") || workflow.endsWith(".yaml") || workflow.matches(Regex("\\d+"))) {
            "Workflow should be a .yml/.yaml filename or workflow ID."
        }

        onStatus("Checking repository and workflow…")
        val encodedWorkflow = java.net.URLEncoder.encode(workflow, "UTF-8")
        val encodedBranch = java.net.URLEncoder.encode(branch, "UTF-8")
        val runStartedAt = Instant.now()

        val dispatchBody = JSONObject().put("ref", branch).toString()
        request(
            "POST",
            "/repos/$owner/$repo/actions/workflows/$encodedWorkflow/dispatches",
            dispatchBody
        ).disconnect()

        onStatus("Workflow dispatched. Waiting for GitHub to create a run…")
        var runId: Long? = null
        var runStatus = ""
        var runConclusion = ""
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000L

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(4000)
            val runsConnection = request(
                "GET",
                "/repos/$owner/$repo/actions/runs?event=workflow_dispatch&branch=$encodedBranch&per_page=20"
            )
            val runsJson = runsConnection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            runsConnection.disconnect()
            val runs = runsJson.optJSONArray("workflow_runs")
            if (runs != null) {
                for (i in 0 until runs.length()) {
                    val run = runs.getJSONObject(i)
                    val runPath = run.optString("path")
                    if (!runPath.contains(workflow) && !workflow.matches(Regex("\\d+"))) continue
                    val created = run.optString("created_at")
                    val createdInstant = try { Instant.parse(created) } catch (_: DateTimeParseException) { Instant.EPOCH }
                    if (!createdInstant.isBefore(runStartedAt.minusSeconds(5))) {
                        runId = run.optLong("id").takeIf { it > 0 }
                        if (runId != null) {
                            runStatus = run.optString("status")
                            runConclusion = run.optString("conclusion")
                            break
                        }
                    }
                }
            }

            if (runId != null) {
                if (runStatus == "completed") break
                onStatus("Build running… status: $runStatus")
            } else {
                onStatus("Waiting for workflow run to appear…")
            }
        }

        val finalRunId = runId ?: throw IllegalStateException("Timed out waiting for a workflow run. Check Actions are enabled and workflow_dispatch is present.")
        if (runStatus != "completed") {
            throw IllegalStateException("Build timed out. Open GitHub Actions to inspect run $finalRunId.")
        }
        if (runConclusion != "success") {
            throw IllegalStateException("Workflow finished with '$runConclusion'. Open GitHub Actions to inspect run $finalRunId.")
        }

        onStatus("Build succeeded. Finding APK artifact…")
        val artifactConnection = request("GET", "/repos/$owner/$repo/actions/runs/$finalRunId/artifacts?per_page=100")
        val artifactJson = artifactConnection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        artifactConnection.disconnect()
        val artifacts = artifactJson.optJSONArray("artifacts")
            ?: throw IllegalStateException("GitHub returned no artifacts.")
        var artifactId: Long? = null
        for (i in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(i)
            if (artifact.optString("name") == "talk2apk-debug-apk" && !artifact.optBoolean("expired")) {
                artifactId = artifact.optLong("id").takeIf { it > 0 }
                break
            }
        }
        val id = artifactId ?: throw IllegalStateException("No unexpired 'talk2apk-debug-apk' artifact found for run $finalRunId.")

        onStatus("Downloading APK artifact…")
        val zipConnection = request("GET", "/repos/$owner/$repo/actions/artifacts/$id/zip", accept = "application/vnd.github+json")
        val zipFile = File(cacheDir, "talk2apk-artifact-$id.zip")
        zipConnection.inputStream.use { input -> FileOutputStream(zipFile).use { output -> input.copyTo(output) } }
        zipConnection.disconnect()

        val apk = File(cacheDir, "talk2apk-debug-$id.apk")
        var extracted = false
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    FileOutputStream(apk).use { output -> zis.copyTo(output) }
                    extracted = true
                    break
                }
            }
        }
        zipFile.delete()
        if (!extracted || !apk.exists() || apk.length() == 0L) {
            apk.delete()
            throw IllegalStateException("Artifact downloaded, but no APK was found inside it.")
        }
        return apk
    }
}
