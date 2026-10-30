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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    private var pendingInstall: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecretStore.initialize(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Talk2ApkApp(
                    cacheDir = cacheDir,
                    onInstall = ::installApk,
                    onRequestInstallPermission = ::requestInstallPermission
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pendingInstall?.let {
            if (packageManager.canRequestPackageInstalls()) {
                pendingInstall = null
                launchInstaller(it)
            }
        }
    }

    private fun installApk(file: File) {
        if (!file.exists() || file.length() == 0L) {
            android.widget.Toast.makeText(this, "APK file is missing or empty", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            requestInstallPermission()
            return
        }
        launchInstaller(file)
    }

    private fun requestInstallPermission() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Could not open package installer: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

private data class ChatMessage(val role: String, val content: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Talk2ApkApp(
    cacheDir: File,
    onInstall: (File) -> Unit,
    onRequestInstallPermission: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var workflow by remember { mutableStateOf("android-build.yml") }
    var githubToken by remember { mutableStateOf("") }
    var apiBase by remember { mutableStateOf("https://api.openai.com/v1") }
    var apiModel by remember { mutableStateOf("gpt-4.1-mini") }
    var apiKey by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("You are an expert Android app developer. Help the user plan an Android app. Be concise, ask clarifying questions when needed, and produce clear implementation requirements. Do not claim to have modified or built source code.") }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", "Hi! Describe the Android app you want to build. I can help turn the idea into a clear specification. Source generation and automatic code commits are the next development milestone."))) }
    var status by remember { mutableStateOf("Connect a GitHub repository to build an APK.") }
    var busy by remember { mutableStateOf(false) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        apiKey = SecretStore.get("ai_api_key")
        apiBase = SecretStore.get("ai_api_base").ifBlank { apiBase }
        apiModel = SecretStore.get("ai_model").ifBlank { apiModel }
        owner = SecretStore.get("gh_owner")
        repo = SecretStore.get("gh_repo")
        branch = SecretStore.get("gh_branch").ifBlank { "main" }
        workflow = SecretStore.get("gh_workflow").ifBlank { "android-build.yml" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk2APK") },
                actions = {
                    TextButton(onClick = { tab = 2 }) { Text("Settings") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("✦") }, label = { Text("Chat") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("↥") }, label = { Text("Build") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        when (tab) {
            0 -> Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                Text("AI app planner", style = MaterialTheme.typography.titleMedium)
                Text("Compatible with OpenAI-style /chat/completions APIs. A consumer AI subscription is not automatically an API credential.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages) { msg ->
                        Surface(
                            color = if (msg.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (msg.role == "user") "You" else "AI", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(3.dp))
                                Text(msg.content)
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Describe your app…") },
                        maxLines = 5
                    )
                    Button(
                        enabled = !busy && input.isNotBlank() && apiKey.isNotBlank(),
                        onClick = {
                            val prompt = input.trim()
                            input = ""
                            val updated = messages + ChatMessage("user", prompt)
                            messages = updated
                            busy = true
                            scope.launch {
                                try {
                                    val answer = withContext(Dispatchers.IO) {
                                        OpenAiCompatibleClient(apiBase, apiKey, apiModel).chat(systemPrompt, updated)
                                    }
                                    messages = messages + ChatMessage("assistant", answer)
                                } catch (e: Exception) {
                                    messages = messages + ChatMessage("assistant", "Request failed: ${e.message}")
                                } finally { busy = false }
                            }
                        }
                    ) { Text("Send") }
                }
                if (apiKey.isBlank()) Text("Add an AI API key in Settings to start chatting.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            1 -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("GitHub Actions build", style = MaterialTheme.typography.titleLarge)
                Text("Builds the Android project already in your repository. AI-generated source changes are not wired into the build yet.")
                OutlinedTextField(owner, { owner = it.trim() }, label = { Text("GitHub owner / organization") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(repo, { repo = it.trim() }, label = { Text("Repository") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(branch, { branch = it.trim() }, label = { Text("Branch") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(workflow, { workflow = it.trim() }, label = { Text("Workflow file") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    githubToken, { githubToken = it },
                    label = { Text("Fine-grained GitHub token") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Text("Token: Actions read/write, Contents read-only, Metadata read. Limit access to this repository. Token is used for this session only.", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !busy && owner.isNotBlank() && repo.isNotBlank() && githubToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true; apkFile = null; status = "Dispatching workflow…"
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    GitHubBuildClient(githubToken.trim(), owner.trim(), repo.trim(), branch.trim(), workflow.trim(), cacheDir)
                                        .buildAndDownload { statusText -> scope.launch { status = statusText } }
                                }
                                apkFile = file
                                status = "Build successful. APK downloaded."
                                SecretStore.put("gh_owner", owner); SecretStore.put("gh_repo", repo)
                                SecretStore.put("gh_branch", branch); SecretStore.put("gh_workflow", workflow)
                            } catch (e: Exception) {
                                status = "Build failed: ${e.message}"
                            } finally { busy = false; githubToken = "" }
                        }
                    }
                ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Build APK") }
                if (busy) Text("Keep this screen open while the build runs.")
                Text(status, style = MaterialTheme.typography.bodyMedium)
                apkFile?.let { file ->
                    Text("Downloaded: ${file.name}")
                    Button(onClick = { onInstall(file) }, modifier = Modifier.fillMaxWidth()) { Text("Install APK") }
                    TextButton(onClick = onRequestInstallPermission) { Text("Manage install permission") }
                }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("AI provider", style = MaterialTheme.typography.titleLarge)
                Text("This adapter currently supports OpenAI-compatible chat completions endpoints. Use an API key issued for that endpoint; ChatGPT/Claude consumer subscriptions do not automatically grant API access.")
                OutlinedTextField(apiBase, { apiBase = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(apiModel, { apiModel = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    apiKey, { apiKey = it }, label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(systemPrompt, { systemPrompt = it }, label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = {
                    SecretStore.put("ai_api_key", apiKey)
                    SecretStore.put("ai_api_base", apiBase.trimEnd('/'))
                    SecretStore.put("ai_model", apiModel)
                    status = "AI provider settings saved securely on this device."
                }, modifier = Modifier.fillMaxWidth()) { Text("Save AI settings") }
                Text("Secrets are encrypted with an Android Keystore AES-GCM key. API keys are sent only to the configured endpoint. Avoid untrusted endpoints.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Divider()
                Text("GitHub security", style = MaterialTheme.typography.titleMedium)
                Text("GitHub token is not persisted. For production, replace fine-grained PAT entry with a GitHub App authorization flow and server-side token exchange.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    fun chat(system: String, history: List<ChatMessage>): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "API URL must use HTTPS." }
        require(model.isNotBlank()) { "Model ID is required." }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.takeLast(30).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.4)
        val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${response.take(700)}")
            val root = JSONObject(response)
            return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally { connection.disconnect() }
    }
}

/** AES/GCM encryption backed by a non-exportable Android Keystore key. */
private object SecretStore {
    private const val PREFS = "talk2apk_secure"
    private const val KEY_ALIAS = "talk2apk_aes_key"

    private fun key(): java.security.Key {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? java.security.Key)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) ?: return
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        val encoded = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
        prefs.edit().putString(name, encoded).apply()
    }

    fun get(name: String): String {
        val encoded = appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.getString(name, null) ?: return ""
        return try {
            val payload = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private var appContext: android.content.Context? = null
    fun initialize(context: android.content.Context) { appContext = context.applicationContext }
}
