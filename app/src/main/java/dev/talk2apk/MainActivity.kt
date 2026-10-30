package dev.talk2apk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private var pendingInstall: File? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecretStore.initialize(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Talk2ApkApp(applicationContext.filesDir, cacheDir, ::installApk, ::requestInstallPermission)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        pendingInstall?.let { if (packageManager.canRequestPackageInstalls()) { pendingInstall = null; launchInstaller(it) } }
    }
    private fun installApk(file: File) {
        if (!file.exists() || file.length() == 0L) { toast("APK file is missing or empty"); return }
        if (!packageManager.canRequestPackageInstalls()) { pendingInstall = file; requestInstallPermission(); return }
        launchInstaller(file)
    }
    private fun requestInstallPermission() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }
    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true).putExtra(Intent.EXTRA_RETURN_RESULT, true))
        } catch (e: Exception) { toast("Could not open package installer: ${e.message}") }
    }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Talk2ApkApp(filesDir: File, cacheDir: File, onInstall: (File) -> Unit, onRequestInstallPermission: () -> Unit) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("talk2apk_settings", android.content.Context.MODE_PRIVATE)
    var tab by remember { mutableIntStateOf(0) }
    var projectName by remember { mutableStateOf("my-android-app") }
    var apiBase by remember { mutableStateOf("https://api.openai.com/v1") }
    var apiModel by remember { mutableStateOf("gpt-4.1-mini") }
    var apiKey by remember { mutableStateOf("") }
    var githubOwner by remember { mutableStateOf("") }
    var githubRepo by remember { mutableStateOf("") }
    var githubBranch by remember { mutableStateOf("main") }
    var githubWorkflow by remember { mutableStateOf("android-build.yml") }
    var githubToken by remember { mutableStateOf("") }
    var settingsLoaded by remember { mutableStateOf(false) }
    var customSystem by remember { mutableStateOf(AiFileProtocol.systemPrompt) }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", "Welcome to Talk2APK. Describe the Android app you want to create or change. I can generate project files, show a short change summary, and wait for your approval before publishing."))) }
    var pending by remember { mutableStateOf<List<FileChange>>(emptyList()) }
    var status by remember { mutableStateOf("Configure your AI provider and GitHub repository in Settings.") }
    var busy by remember { mutableStateOf(false) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val projectStore = remember(projectName) { ProjectStore(context, projectName) }

    LaunchedEffect(projectStore.root.absolutePath) {
        pending = projectStore.loadPending()
    }

    LaunchedEffect(Unit) {
        projectName = prefs.getString("project_name", "my-android-app") ?: "my-android-app"
        apiBase = SecretStore.get("ai_api_base").ifBlank { apiBase }
        apiModel = SecretStore.get("ai_model").ifBlank { apiModel }
        apiKey = SecretStore.get("ai_api_key")
        githubOwner = SecretStore.get("gh_owner")
        githubRepo = SecretStore.get("gh_repo")
        githubBranch = SecretStore.get("gh_branch").ifBlank { "main" }
        githubWorkflow = SecretStore.get("gh_workflow").ifBlank { "android-build.yml" }
        githubToken = SecretStore.get("gh_token")
        customSystem = SecretStore.get("ai_system_prompt").ifBlank { AiFileProtocol.systemPrompt }
        val saved = prefs.getString("chat_history", null)
        if (!saved.isNullOrBlank()) try {
            val arr = JSONArray(saved)
            messages = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { ChatMessage(it.getString("role"), it.getString("content")) } }
        } catch (_: Exception) { }
        settingsLoaded = true
    }

    fun persistSettings() {
        SecretStore.put("ai_api_base", apiBase.trim().trimEnd('/'))
        SecretStore.put("ai_model", apiModel.trim())
        SecretStore.put("ai_api_key", apiKey.trim())
        SecretStore.put("gh_owner", githubOwner.trim())
        SecretStore.put("gh_repo", githubRepo.trim())
        SecretStore.put("gh_branch", githubBranch.trim())
        SecretStore.put("gh_workflow", githubWorkflow.trim())
        SecretStore.put("gh_token", githubToken.trim())
        SecretStore.put("ai_system_prompt", customSystem)
        prefs.edit().putString("project_name", projectName).apply()
    }
    fun saveSettings() {
        persistSettings()
        status = "Settings saved securely on this device."
    }
    LaunchedEffect(settingsLoaded, projectName, apiBase, apiModel, apiKey, githubOwner, githubRepo, githubBranch, githubWorkflow, githubToken, customSystem) {
        if (settingsLoaded) {
            kotlinx.coroutines.delay(700)
            persistSettings()
        }
    }
    fun saveHistory(list: List<ChatMessage>) {
        val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        prefs.edit().putString("chat_history", arr.toString()).apply()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Talk2APK") }, actions = { TextButton(onClick = { tab = 2 }) { Text("Settings") } }) },
        bottomBar = { NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("✦") }, label = { Text("Chat") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("↥") }, label = { Text("Build") })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("Settings") })
        } }
    ) { padding ->
        when (tab) {
            0 -> Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                Text("Project: ${projectStore.root.name}", style = MaterialTheme.typography.titleMedium)
                Text("AI changes are staged locally. Review the per-file summary, then publish when ready.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages) { msg ->
                        Surface(color = if (msg.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (msg.role == "user") "You" else "AI", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(3.dp)); Text(msg.content)
                            }
                        }
                    }
                }
                if (pending.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Changes ready for review", style = MaterialTheme.typography.titleMedium)
                            val summaries = projectStore.summaries(pending)
                            summaries.forEach { s ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.path, style = MaterialTheme.typography.bodySmall)
                                        Text(if (s.action.equals("delete", true)) "File deletion" else "${s.added} lines added · ${s.removed} removed",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Text("${pending.size} file operation(s). Local files will be updated after successful publishing.", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !busy && githubToken.isNotBlank() && githubOwner.isNotBlank() && githubRepo.isNotBlank(),
                                onClick = {
                                    busy = true; status = "Publishing source changes to GitHub…"
                                    scope.launch {
                                        try {
                                            val sha = withContext(Dispatchers.IO) {
                                                GitHubPublisher(githubToken, githubOwner, githubRepo, githubBranch).publish(pending)
                                            }
                                            projectStore.apply(pending)
                                            projectStore.clearPending()
                                            status = "Published commit ${sha.take(7)} to $githubOwner/$githubRepo ($githubBranch). You can now build the APK."
                                            pending = emptyList()
                                        } catch (e: Exception) { status = "Publish failed: ${e.message}" }
                                        finally { busy = false }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Publish to GitHub") }
                            TextButton(enabled = !busy, onClick = { pending = emptyList(); projectStore.clearPending(); status = "Pending changes discarded. Local project was not modified." }) { Text("Discard changes") }
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Describe a feature or change…") }, maxLines = 4, enabled = !busy && pending.isEmpty())
                    Button(enabled = !busy && pending.isEmpty() && input.isNotBlank() && apiKey.isNotBlank(), onClick = {
                        val prompt = input.trim(); input = ""
                        val updated = messages + ChatMessage("user", prompt); messages = updated; saveHistory(updated); busy = true
                        scope.launch {
                            try {
                                val reply = withContext(Dispatchers.IO) {
                                    if (githubToken.isNotBlank() && githubOwner.isNotBlank() && githubRepo.isNotBlank()) {
                                        GitHubPublisher(githubToken, githubOwner, githubRepo, githubBranch).syncProjectIfEmpty(projectStore)
                                    }
                                    AiClient(apiBase, apiKey, apiModel).send(customSystem, updated, projectStore.contextForAi())
                                }
                                messages = messages + ChatMessage("assistant", reply.text)
                                saveHistory(messages)
                                if (reply.changes.isNotEmpty()) {
                                    // Validate every path and operation before presenting a review.
                                    reply.changes.forEach { change -> projectStore.validatePath(change.path) }
                                    pending = reply.changes
                            projectStore.savePending(reply.changes)
                                }
                            } catch (e: Exception) { messages = messages + ChatMessage("assistant", "Request failed: ${e.message}"); saveHistory(messages) }
                            finally { busy = false }
                        }
                    }) { Text("Send") }
                }
                if (apiKey.isBlank()) Text("Add an AI API key in Settings.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            1 -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("GitHub Actions build", style = MaterialTheme.typography.titleLarge)
                Text("Repository: ${githubOwner.ifBlank { "—" }}/${githubRepo.ifBlank { "—" }} · branch $githubBranch")
                Text(if (pending.isNotEmpty()) "You have unpublished source changes. Publish them from Chat before building." else "Publish source changes from Chat first. Then start the remote Gradle build here.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && pending.isEmpty() && githubToken.isNotBlank() && githubOwner.isNotBlank() && githubRepo.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true; apkFile = null; status = "Dispatching GitHub Actions workflow…"
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) {
                                GitHubBuildClient(githubToken, githubOwner, githubRepo, githubBranch, githubWorkflow, cacheDir)
                                    .buildAndDownload { text -> scope.launch { status = text } }
                            }
                            apkFile = file; status = "Build successful. APK downloaded: ${file.name}"
                        } catch (e: Exception) { status = "Build failed: ${e.message}" }
                        finally { busy = false }
                    }
                }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Build APK") }
                Text(status)
                apkFile?.let { file ->
                    Button(onClick = { onInstall(file) }, modifier = Modifier.fillMaxWidth()) { Text("Install APK") }
                    TextButton(onClick = onRequestInstallPermission) { Text("Manage install permission") }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Project", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(projectName, { projectName = it }, label = { Text("Project name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Divider()
                Text("AI provider", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(apiBase, { apiBase = it }, label = { Text("API base URL (HTTPS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(apiModel, { apiModel = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("AI API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(customSystem, { customSystem = it }, label = { Text("System instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                Divider()
                Text("GitHub", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(githubOwner, { githubOwner = it.trim() }, label = { Text("Owner / organization") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(githubRepo, { githubRepo = it.trim() }, label = { Text("Repository") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(githubBranch, { githubBranch = it }, label = { Text("Branch") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(githubWorkflow, { githubWorkflow = it }, label = { Text("Workflow filename or ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(githubToken, { githubToken = it }, label = { Text("Fine-grained GitHub token") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Recommended token permissions for this repository: Contents read/write and Actions read/write. Metadata read is automatic. Tokens are encrypted on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { saveSettings() }, modifier = Modifier.fillMaxWidth()) { Text("Save all settings") }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("AI endpoints must support OpenAI-compatible Chat Completions and function calling, or return the documented JSON file format. Consumer AI subscriptions do not automatically include API access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** AES/GCM encryption backed by a non-exportable Android Keystore key. */
private object SecretStore {
    private const val PREFS = "talk2apk_secure"
    private const val KEY_ALIAS = "talk2apk_aes_key"
    private var appContext: android.content.Context? = null
    fun initialize(context: android.content.Context) { appContext = context.applicationContext }
    private fun key(): java.security.Key {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? java.security.Key)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build()
        gen.init(spec); return gen.generateKey()
    }
    fun put(name: String, value: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) ?: return
        if (value.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)).apply()
    }
    fun get(name: String): String {
        val encoded = appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.getString(name, null) ?: return ""
        return try {
            val payload = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}

private class GitHubBuildClient(private val token: String, private val owner: String, private val repo: String,
    private val branch: String, private val workflow: String, private val cacheDir: File) {
    private val api = "https://api.github.com"
    private fun request(method: String, path: String, body: String? = null, accept: String = "application/vnd.github+json"): HttpURLConnection {
        val c = (URL(api + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28"); setRequestProperty("User-Agent", "Talk2APK")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(body.toByteArray()) } }
        }
        val code = c.responseCode
        if (code !in 200..299) { val msg = c.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"; c.disconnect(); throw IllegalStateException("GitHub API HTTP $code: ${msg.take(500)}") }
        return c
    }
    fun buildAndDownload(onStatus: (String) -> Unit): File {
        require(owner.matches(Regex("[A-Za-z0-9-]+")) && repo.matches(Regex("[A-Za-z0-9._-]+"))) { "Enter valid GitHub owner and repository" }
        require(branch.isNotBlank()) { "Branch cannot be empty" }
        require(workflow.endsWith(".yml") || workflow.endsWith(".yaml") || workflow.matches(Regex("\\d+"))) { "Workflow must be a .yml/.yaml filename or ID" }
        onStatus("Dispatching workflow…")
        val wf = java.net.URLEncoder.encode(workflow, "UTF-8"); val br = java.net.URLEncoder.encode(branch, "UTF-8")
        val started = Instant.now()
        request("POST", "/repos/$owner/$repo/actions/workflows/$wf/dispatches", JSONObject().put("ref", branch).toString()).disconnect()
        onStatus("Waiting for GitHub Actions…")
        var runId: Long? = null; var runStatus = ""; var conclusion = ""
        val deadline = System.currentTimeMillis() + 15 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(4000)
            val c = request("GET", "/repos/$owner/$repo/actions/runs?event=workflow_dispatch&branch=$br&per_page=20")
            val data = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }; c.disconnect()
            val runs = data.optJSONArray("workflow_runs")
            if (runs != null) for (i in 0 until runs.length()) {
                val r = runs.getJSONObject(i)
                if (!r.optString("path").contains(workflow) && !workflow.matches(Regex("\\d+"))) continue
                val created = try { Instant.parse(r.optString("created_at")) } catch (_: DateTimeParseException) { Instant.EPOCH }
                if (!created.isBefore(started.minusSeconds(5))) { runId = r.optLong("id").takeIf { it > 0 }; runStatus = r.optString("status"); conclusion = r.optString("conclusion"); break }
            }
            if (runId != null && runStatus == "completed") break
            onStatus(if (runId == null) "Waiting for workflow run…" else "Build status: $runStatus")
        }
        val id = runId ?: throw IllegalStateException("Timed out waiting for workflow run. Check workflow_dispatch and token Actions permission.")
        if (runStatus != "completed") throw IllegalStateException("Build timed out. Check GitHub Actions run $id")
        if (conclusion != "success") throw IllegalStateException("Build ended with '$conclusion'. Check GitHub Actions run $id")
        onStatus("Build succeeded. Downloading APK artifact…")
        val ac = request("GET", "/repos/$owner/$repo/actions/runs/$id/artifacts?per_page=100")
        val arr = ac.inputStream.bufferedReader().use { JSONObject(it.readText()).optJSONArray("artifacts") ?: JSONArray() }; ac.disconnect()
        var artifactId: Long? = null
        for (i in 0 until arr.length()) { val a = arr.getJSONObject(i); if (a.optString("name") == "talk2apk-debug-apk" && !a.optBoolean("expired")) { artifactId = a.optLong("id"); break } }
        val aid = artifactId ?: throw IllegalStateException("No unexpired talk2apk-debug-apk artifact found")
        val z = request("GET", "/repos/$owner/$repo/actions/artifacts/$aid/zip")
        val zip = File(cacheDir, "talk2apk-$aid.zip"); z.inputStream.use { input -> FileOutputStream(zip).use { input.copyTo(it) } }; z.disconnect()
        val apk = File(cacheDir, "talk2apk-$aid.apk"); var found = false
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis -> while (true) { val e = zis.nextEntry ?: break; if (!e.isDirectory && e.name.endsWith(".apk")) { FileOutputStream(apk).use { zis.copyTo(it) }; found = true; break } } }
        zip.delete(); if (!found || !apk.exists() || apk.length() == 0L) throw IllegalStateException("Downloaded artifact did not contain an APK")
        return apk
    }
}
