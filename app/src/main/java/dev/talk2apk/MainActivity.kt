package dev.talk2apk

import android.content.Context
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var pendingInstall: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecretStore.initialize(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Talk2ApkApp(this@MainActivity, cacheDir, ::installApk, ::requestInstallPermission)
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
            startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true))
        } catch (e: Exception) { toast("Could not open package installer: ${e.message}") }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}

private object BuildStateStore {
    private fun prefs(context: Context) = context.getSharedPreferences("talk2apk_builds", Context.MODE_PRIVATE)
    fun save(context: Context, projectId: String, state: BuildSnapshot) {
        val obj = JSONObject().put("runId", state.runId).put("status", state.status)
            .put("conclusion", state.conclusion).put("htmlUrl", state.htmlUrl)
            .put("artifactId", state.artifactId ?: JSONObject.NULL).put("message", state.message)
            .put("errorDetails", state.errorDetails)
        prefs(context).edit().putString(projectId, obj.toString()).apply()
    }
    fun load(context: Context, projectId: String): BuildSnapshot? {
        return try {
            val raw = prefs(context).getString(projectId, null) ?: return null
            val o = JSONObject(raw)
            BuildSnapshot(o.getLong("runId"), o.optString("status", "queued"), o.optString("conclusion"),
                o.optString("htmlUrl"), o.optLong("artifactId").takeIf { !o.isNull("artifactId") && it > 0 },
                o.optString("message"), o.optString("errorDetails"))
        } catch (_: Exception) { null }
    }
    fun clear(context: Context, projectId: String) { prefs(context).edit().remove(projectId).apply() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Talk2ApkApp(context: Context, cacheDir: File, onInstall: (File) -> Unit, onRequestInstallPermission: () -> Unit) {
    val prefs = remember { context.getSharedPreferences("talk2apk_settings", Context.MODE_PRIVATE) }
    val profileStore = remember { ProjectProfiles(context) }
    val initialProfiles = remember {
        profileStore.load().ifEmpty {
            // One-time migration from the single-project v0.3.x settings.
            val legacyName = prefs.getString("project_name", "my-android-app") ?: "my-android-app"
            val p = ProjectProfile(
                name = legacyName,
                owner = SecretStore.get("gh_owner"), repo = SecretStore.get("gh_repo"),
                branch = SecretStore.get("gh_branch").ifBlank { "main" },
                workflow = SecretStore.get("gh_workflow").ifBlank { "android-build.yml" }
            )
            profileStore.save(listOf(p), p.id)
            prefs.getString("chat_history", null)?.let { prefs.edit().putString("chat_history_${p.id}", it).apply() }
            p.let(::listOf)
        }
    }
    val initialActive = initialProfiles.firstOrNull { it.id == profileStore.activeId() } ?: initialProfiles.first()

    var profiles by remember { mutableStateOf(initialProfiles) }
    var project by remember { mutableStateOf(initialActive) }
    var tab by remember { mutableIntStateOf(0) }
    var projectMenu by remember { mutableStateOf(false) }
    var showProjectManager by remember { mutableStateOf(false) }
    var showCreateProject by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProjectProfile?>(null) }

    var globalAiBase by remember { mutableStateOf(SecretStore.get("global_ai_base").ifBlank { SecretStore.get("ai_api_base").ifBlank { "https://api.openai.com/v1" } }) }
    var globalAiModel by remember { mutableStateOf(SecretStore.get("global_ai_model").ifBlank { SecretStore.get("ai_model").ifBlank { "gpt-4.1-mini" } }) }
    var globalAiKey by remember { mutableStateOf(SecretStore.get("global_ai_key").ifBlank { SecretStore.get("ai_api_key") }) }

    var localAiKey by remember(project.id) { mutableStateOf(SecretStore.get("project_${project.id}_local_ai_key")) }
    var githubToken by remember(project.id) { mutableStateOf(SecretStore.get("project_${project.id}_github_token").ifBlank { if (project.id == initialActive.id) SecretStore.get("gh_token") else "" }) }
    var systemPrompt by remember(project.id) {
        mutableStateOf(SecretStore.get("project_${project.id}_system_prompt").ifBlank {
            if (project.id == initialActive.id) SecretStore.get("ai_system_prompt").ifBlank { AiFileProtocol.systemPrompt } else AiFileProtocol.systemPrompt
        })
    }

    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(loadHistory(prefs, project.id)) }
    var pending by remember { mutableStateOf<List<FileChange>>(emptyList()) }
    var status by remember { mutableStateOf("Select a project, configure its GitHub repository, and start building.") }
    var busy by remember { mutableStateOf(false) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var buildState by remember { mutableStateOf(BuildStateStore.load(context, project.id)) }
    var buildErrorExpanded by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val monitoredBuilds = remember { mutableSetOf<String>() }
    val projectStore = remember(project.id, project.name) { ProjectStore(context, project.name) }

    fun saveHistoryFor(id: String, list: List<ChatMessage>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        prefs.edit().putString("chat_history_$id", arr.toString()).apply()
    }

    fun persistProject(p: ProjectProfile = project) {
        val updated = profiles.map { if (it.id == p.id) p else it }
        profiles = updated
        profileStore.save(updated, p.id)
        SecretStore.put("project_${p.id}_github_token", if (p.id == project.id) githubToken.trim() else SecretStore.get("project_${p.id}_github_token"))
        if (p.id == project.id) {
            SecretStore.put("project_${p.id}_local_ai_key", localAiKey.trim())
            SecretStore.put("project_${p.id}_system_prompt", systemPrompt)
        }
        SecretStore.put("global_ai_base", globalAiBase.trim().trimEnd('/'))
        SecretStore.put("global_ai_model", globalAiModel.trim())
        SecretStore.put("global_ai_key", globalAiKey.trim())
    }

    fun activateProfile(next: ProjectProfile) {
        persistProject()
        project = next
        profileStore.save(profiles, next.id)
        messages = loadHistory(prefs, next.id)
        pending = emptyList()
        apkFile = null
        buildState = BuildStateStore.load(context, next.id)
        status = "Project switched to ${next.name}."
        tab = 0
    }

    fun createProject(nameInput: String) {
        val name = nameInput.trim()
        require(name.isNotBlank()) { "Enter a project name" }
        val safe = ProjectStore.safeProjectName(name)
        require(profiles.none { ProjectStore.safeProjectName(it.name).equals(safe, true) }) { "A project with that name already exists" }
        val p = ProjectProfile(name = name)
        profiles = profiles + p
        profileStore.save(profiles, p.id)
        activateProfile(p)
    }

    fun cloneProject(source: ProjectProfile) {
        val baseName = "${source.name}-copy"
        var candidate = baseName
        var suffix = 2
        while (profiles.any { ProjectStore.safeProjectName(it.name).equals(ProjectStore.safeProjectName(candidate), true) }) {
            candidate = "$baseName-$suffix"; suffix++
        }
        ProjectDirectories.clone(context, source.name, candidate)
        val cloned = source.copy(id = UUID.randomUUID().toString(), name = candidate, owner = "", repo = "")
        val clonedStore = ProjectStore(context, candidate)
        val staged = clonedStore.loadPending()
        if (staged.isNotEmpty()) { clonedStore.apply(staged); clonedStore.clearPending() }
        profiles = profiles + cloned
        profileStore.save(profiles, cloned.id)
        SecretStore.put("project_${cloned.id}_local_ai_key", SecretStore.get("project_${source.id}_local_ai_key"))
        SecretStore.put("project_${cloned.id}_system_prompt", SecretStore.get("project_${source.id}_system_prompt"))
        SecretStore.put("project_${cloned.id}_github_token", "")
        activateProfile(cloned)
        status = "Cloned ${source.name}. Configure a new GitHub repository before publishing."
    }

    fun removeProject(target: ProjectProfile) {
        if (profiles.size <= 1) { status = "Create another project before deleting the last one."; return }
        ProjectDirectories.delete(context, target.name)
        SecretStore.remove("project_${target.id}_github_token")
        SecretStore.remove("project_${target.id}_local_ai_key")
        SecretStore.remove("project_${target.id}_system_prompt")
        BuildStateStore.clear(context, target.id)
        prefs.edit().remove("chat_history_${target.id}").apply()
        profiles = profiles.filterNot { it.id == target.id }
        val next = if (project.id == target.id) profiles.first() else project
        profileStore.save(profiles, next.id)
        if (project.id == target.id) {
            project = next
            messages = loadHistory(prefs, next.id)
            pending = emptyList()
            apkFile = null
            buildState = BuildStateStore.load(context, next.id)
        }
        status = "Deleted local project ${target.name}. Its GitHub repository was not deleted."
    }

    fun saveSettings() {
        persistProject(project)
        status = "Settings saved for ${project.name}. AI settings and credentials are stored on this device."
    }

    fun buildClient(p: ProjectProfile, token: String) = GitHubBuildClient(token, p.owner, p.repo, p.branch, p.workflow, cacheDir)

    fun monitorBuild(p: ProjectProfile, token: String, runId: Long, htmlUrl: String) {
        if (!monitoredBuilds.add(p.id)) return
        scope.launch {
            try {
                var transientFailures = 0
                while (true) {
                try {
                    val latest = withContext(Dispatchers.IO) { buildClient(p, token).getSnapshot(runId) }
                    BuildStateStore.save(context, p.id, latest)
                    if (project.id == p.id) {
                        buildState = latest
                        status = latest.message
                    }
                    transientFailures = 0
                    if (latest.completed && (latest.conclusion != "success" || latest.artifactId != null)) break
                } catch (e: Exception) {
                    transientFailures++
                    if (project.id == p.id) status = "Can't refresh GitHub status (${e.message}). Retrying automatically…"
                    if (transientFailures >= 12) {
                        val failed = BuildSnapshot(runId, "completed", "error", htmlUrl,
                            message = "Stopped monitoring after repeated GitHub API errors.",
                            errorDetails = "${e.message}\nCheck your token, network connection and GitHub Actions run.")
                        BuildStateStore.save(context, p.id, failed)
                        if (project.id == p.id) buildState = failed
                        break
                    }
                }
                    delay(5000)
                }
            } finally {
                monitoredBuilds.remove(p.id)
            }
        }
    }

    LaunchedEffect(project.id, project.name) {
        pending = projectStore.loadPending()
        buildState = BuildStateStore.load(context, project.id)
        if (pending.isNotEmpty()) status = "Restored ${pending.size} pending change(s) for ${project.name}."
        val savedBuild = buildState
        if (savedBuild != null && (!savedBuild.completed || (savedBuild.conclusion == "success" && savedBuild.artifactId == null)) && githubToken.isNotBlank()) {
            monitorBuild(project, githubToken, savedBuild.runId, savedBuild.htmlUrl)
        }
    }

    LaunchedEffect(project, githubToken, localAiKey, systemPrompt, globalAiBase, globalAiModel, globalAiKey) {
        delay(600)
        persistProject(project)
    }

    val effectiveAiBase = if (project.useLocalAi) project.localAiBase else globalAiBase
    val effectiveAiModel = if (project.useLocalAi) project.localAiModel else globalAiModel
    val effectiveAiKey = if (project.useLocalAi) localAiKey else globalAiKey

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        TextButton(enabled = !busy, onClick = { projectMenu = true }) { Text("${project.name} ▾", maxLines = 1) }
                        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                            profiles.forEach { p ->
                                DropdownMenuItem(enabled = !busy, text = { Text(if (p.id == project.id) "✓ ${p.name}" else p.name) },
                                    onClick = { projectMenu = false; if (!busy && p.id != project.id) activateProfile(p) })
                            }
                            Divider()
                            DropdownMenuItem(enabled = !busy, text = { Text("Manage projects…") }, onClick = { projectMenu = false; if (!busy) showProjectManager = true })
                        }
                    }
                },
                actions = { TextButton(onClick = { tab = 2 }) { Text("Settings") } }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("✦") }, label = { Text("Chat") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("✓") }, label = { Text("Changes / Build") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        when (tab) {
            0 -> Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                Text("${project.name} · ${project.owner.ifBlank { "GitHub not configured" }}/${project.repo}",
                    style = MaterialTheme.typography.titleSmall)
                Text(if (project.useLocalAi) "AI engine: project-specific" else "AI engine: global default",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Describe a feature or change…") }, maxLines = 4, enabled = !busy && pending.isEmpty())
                    Button(enabled = !busy && pending.isEmpty() && input.isNotBlank() && effectiveAiKey.isNotBlank(), onClick = {
                        val prompt = input.trim(); input = ""
                        val history = messages + ChatMessage("user", prompt)
                        messages = history; saveHistoryFor(project.id, history)
                        busy = true; status = "Preparing project context…"; tab = 1
                        val projectSnapshot = project
                        val tokenSnapshot = githubToken
                        val aiBaseSnapshot = effectiveAiBase
                        val aiModelSnapshot = effectiveAiModel
                        val aiKeySnapshot = effectiveAiKey
                        val systemSnapshot = systemPrompt
                        scope.launch {
                            try {
                                val contextAndWorkflow = withContext(Dispatchers.IO) {
                                    if (tokenSnapshot.isNotBlank() && projectSnapshot.owner.isNotBlank() && projectSnapshot.repo.isNotBlank()) {
                                        GitHubPublisher(tokenSnapshot, projectSnapshot.owner, projectSnapshot.repo, projectSnapshot.branch)
                                            .syncProjectIfEmpty(projectStore)
                                    }
                                    val configuredWorkflow = projectSnapshot.workflow
                                    val workflowPath = if (configuredWorkflow.endsWith(".yml") || configuredWorkflow.endsWith(".yaml")) {
                                        ".github/workflows/${configuredWorkflow.substringAfterLast('/')}"
                                    } else DefaultBuildWorkflow.path
                                    val workflowChange = if (projectStore.read(workflowPath).isBlank()) {
                                        FileChange(workflowPath, "write", DefaultBuildWorkflow.content)
                                    } else null
                                    val baseContext = projectStore.contextForAi()
                                    val fullContext = if (workflowChange != null) baseContext + "\n\nDEFAULT CI WORKFLOW TEMPLATE (will be included in the staged changes unless you return a replacement for this exact path):\n--- FILE: ${workflowChange.path} ---\n${workflowChange.content}"
                                        else baseContext
                                    fullContext to workflowChange
                                }
                                val projectContext = contextAndWorkflow.first
                                val defaultWorkflowChange = contextAndWorkflow.second
                                status = "Waiting for AI response…"
                                val reply = withContext(Dispatchers.IO) {
                                    AiClient(aiBaseSnapshot, aiKeySnapshot, aiModelSnapshot).send(systemSnapshot, history, projectContext)
                                }
                                messages = messages + ChatMessage("assistant", reply.text)
                                saveHistoryFor(projectSnapshot.id, messages)
                                val stagedChanges = reply.changes.toMutableList()
                                if (defaultWorkflowChange != null && stagedChanges.none { it.path == defaultWorkflowChange.path }) {
                                    stagedChanges.add(0, defaultWorkflowChange)
                                }
                                if (stagedChanges.isNotEmpty()) {
                                    status = "Validating generated files…"
                                    stagedChanges.forEach { change ->
                                        projectStore.validatePath(change.path)
                                        require(change.action.lowercase() in setOf("write", "create", "update", "delete", "remove")) {
                                            "Unsupported operation for ${change.path}"
                                        }
                                    }
                                    projectStore.savePending(stagedChanges)
                                    pending = stagedChanges
                                    status = "${stagedChanges.size} file operation(s) ready for review."
                                } else status = "AI response received. No source-file changes were returned."
                            } catch (e: Exception) {
                                val failed = messages + ChatMessage("assistant", "Request failed: ${e.message}")
                                messages = failed; saveHistoryFor(projectSnapshot.id, failed)
                                status = "Request failed: ${e.message}"
                            } finally { busy = false }
                        }
                    }) { Text("Send") }
                }
                if (effectiveAiKey.isBlank()) Text("Configure the selected AI engine in Settings.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (pending.isNotEmpty()) TextButton(onClick = { tab = 1 }) { Text("Review ${pending.size} pending operation(s)") }
            }

            1 -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${project.name} · Changes & Build", style = MaterialTheme.typography.titleLarge)
                Text("Repository: ${project.owner.ifBlank { "—" }}/${project.repo.ifBlank { "—" }} · ${project.branch}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (busy || (buildState != null && (!buildState!!.completed || (buildState!!.conclusion == "success" && buildState!!.artifactId == null)))) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(when {
                                busy -> "WORKING"
                                buildState?.succeeded == true -> "BUILD SUCCESSFUL"
                                buildState?.completed == true && buildState?.conclusion == "success" -> "BUILD OK · FINDING ARTIFACT"
                                buildState?.completed == true -> "BUILD ERROR"
                                buildState != null -> "BUILD IN PROGRESS"
                                pending.isNotEmpty() -> "REVIEW REQUIRED"
                                else -> "READY"
                            }, style = MaterialTheme.typography.labelLarge, color = if (buildState?.completed == true && buildState?.conclusion != "success") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Text(if (buildState != null && buildState!!.runId > 0) buildState!!.message else status, style = MaterialTheme.typography.bodyMedium)
                        if (busy || (buildState != null && (!buildState!!.completed || (buildState!!.conclusion == "success" && buildState!!.artifactId == null)))) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (buildState?.htmlUrl?.isNotBlank() == true) TextButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(buildState!!.htmlUrl))) } catch (_: Exception) { }
                        }) { Text("Open GitHub Actions run") }
                    }
                }

                Text("Step 1 · Review changes", style = MaterialTheme.typography.titleMedium)
                if (pending.isNotEmpty()) {
                    val summaries = projectStore.summaries(pending)
                    Text("${pending.size} operation(s) · ${summaries.count { it.action.lowercase() !in setOf("delete", "remove") }} written · ${summaries.count { it.action.lowercase() in setOf("delete", "remove") }} deleted",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            summaries.forEach { summary ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(summary.path, style = MaterialTheme.typography.bodySmall)
                                        Text(if (summary.action.lowercase() in setOf("delete", "remove")) "File will be deleted" else "+${summary.added} lines · −${summary.removed} lines",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(if (summary.action.lowercase() in setOf("delete", "remove")) "DELETE" else "EDIT",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    Text("Files are staged locally. Publishing is a separate action.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy && githubToken.isNotBlank() && project.owner.isNotBlank() && project.repo.isNotBlank(),
                        onClick = {
                            busy = true; status = "Publishing approved changes to GitHub…"
                            val p = project; val changes = pending.toList(); val token = githubToken
                            scope.launch {
                                try {
                                    val sha = withContext(Dispatchers.IO) {
                                        GitHubPublisher(token, p.owner, p.repo, p.branch).publish(changes)
                                    }
                                    projectStore.apply(changes); projectStore.clearPending(); pending = emptyList()
                                    status = "Published commit ${sha.take(7)}. You can now start the GitHub Actions build."
                                } catch (e: Exception) { status = "Publish failed: ${e.message}" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Publish to GitHub") }
                    TextButton(enabled = !busy, onClick = {
                        pending = emptyList(); projectStore.clearPending(); status = "Pending changes discarded."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Discard changes") }
                } else {
                    Text("No unpublished changes. Generate changes in Chat or build the current GitHub branch.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Divider()
                Text("Step 2 · GitHub Actions build", style = MaterialTheme.typography.titleMedium)
                Button(enabled = !busy && pending.isEmpty() && githubToken.isNotBlank() && project.owner.isNotBlank() && project.repo.isNotBlank() &&
                    (buildState == null || buildState!!.completed && !(buildState!!.conclusion == "success" && buildState!!.artifactId == null)),
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true; apkFile = null; buildState = null; BuildStateStore.clear(context, project.id)
                        status = "Dispatching GitHub Actions workflow…"
                        val p = project; val token = githubToken
                        scope.launch {
                            try {
                                val (runId, url) = withContext(Dispatchers.IO) {
                                    buildClient(p, token).dispatchAndFindRun { stage ->
                                        scope.launch { if (project.id == p.id) status = stage }
                                    }
                                }
                                val initial = BuildSnapshot(runId, "queued", htmlUrl = url, message = "Build queued. Waiting for a GitHub runner…")
                                BuildStateStore.save(context, p.id, initial)
                                buildState = initial
                                status = initial.message
                                monitorBuild(p, token, runId, url)
                            } catch (e: Exception) {
                                status = "Could not start build: ${e.message}"
                                buildState = BuildSnapshot(-1, "completed", "error", message = status, errorDetails = e.message.orEmpty())
                            } finally { busy = false }
                        }
                    }) { Text("Start GitHub Actions build") }

                buildState?.let { state ->
                    if (state.completed && state.conclusion != "success") {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Build failed: ${state.conclusion}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                if (state.errorDetails.isNotBlank()) {
                                    TextButton(onClick = { buildErrorExpanded = !buildErrorExpanded }) {
                                        Text(if (buildErrorExpanded) "Hide error details" else "Show error details")
                                    }
                                    if (buildErrorExpanded) SelectionContainer { Text(state.errorDetails, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                                }
                            }
                        }
                    }
                    if (state.succeeded) {
                        Text("Build completed successfully. GitHub's artifact is a ZIP archive containing the APK.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(enabled = !busy, onClick = {
                            val p = project; val token = githubToken; val artifactId = state.artifactId!!
                            busy = true; status = "Downloading APK artifact ZIP from GitHub…"
                            scope.launch {
                                try {
                                    val file = withContext(Dispatchers.IO) { buildClient(p, token).downloadApk(artifactId) }
                                    apkFile = file; status = "APK downloaded: ${file.name}"
                                } catch (e: Exception) { status = "APK download failed: ${e.message}" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Download APK") }
                    }
                }
                apkFile?.let { file ->
                    Text("Downloaded: ${file.name}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onInstall(file) }, modifier = Modifier.fillMaxWidth()) { Text("Install APK") }
                    TextButton(onClick = onRequestInstallPermission, modifier = Modifier.fillMaxWidth()) { Text("Manage install permission") }
                }
                if (githubToken.isBlank() || project.owner.isBlank() || project.repo.isBlank()) {
                    Text("Configure this project's GitHub owner, repository and token in Settings.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Project settings · ${project.name}", style = MaterialTheme.typography.titleLarge)
                Text("These GitHub settings belong only to this project.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Divider()
                Text("AI engine", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Use a project-specific AI engine", style = MaterialTheme.typography.bodyMedium)
                        Text(if (project.useLocalAi) "This project uses its own endpoint, model and key." else "This project uses the global AI settings below.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = project.useLocalAi, onCheckedChange = { project = project.copy(useLocalAi = it) })
                }
                if (project.useLocalAi) {
                    OutlinedTextField(project.localAiBase, { project = project.copy(localAiBase = it) }, label = { Text("Project AI API base URL") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(project.localAiModel, { project = project.copy(localAiModel = it) }, label = { Text("Project AI model") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(localAiKey, { localAiKey = it }, label = { Text("Project AI API key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                Text("Global AI defaults", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(globalAiBase, { globalAiBase = it }, label = { Text("Global API base URL (HTTPS)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(globalAiModel, { globalAiModel = it }, label = { Text("Global model ID") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(globalAiKey, { globalAiKey = it }, label = { Text("Global AI API key") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(systemPrompt, { systemPrompt = it }, label = { Text("System instructions for this project") },
                    modifier = Modifier.fillMaxWidth(), minLines = 4)

                Divider()
                Text("GitHub repository", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(project.owner, { project = project.copy(owner = it.trim()) }, label = { Text("Owner / organization") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(project.repo, { project = project.copy(repo = it.trim()) }, label = { Text("Repository name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(project.branch, { project = project.copy(branch = it) }, label = { Text("Branch") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(project.workflow, { project = project.copy(workflow = it) }, label = { Text("Workflow filename or ID") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(githubToken, { githubToken = it }, label = { Text("Fine-grained GitHub token") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Recommended token permissions: Contents read/write and Actions read/write. Metadata read is automatic. Each project's token is encrypted separately on this device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { saveSettings() }, modifier = Modifier.fillMaxWidth()) { Text("Save project and AI settings") }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("AI endpoints must support OpenAI-compatible Chat Completions and function calling, or the Talk2APK JSON file protocol. Consumer AI subscriptions do not automatically include API access.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showProjectManager) {
        AlertDialog(
            onDismissRequest = { showProjectManager = false },
            title = { Text("Manage projects") },
            text = {
                Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { newProjectName = ""; showCreateProject = true }, modifier = Modifier.fillMaxWidth()) { Text("Create new project") }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(profiles, key = { it.id }) { p ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (p.id == project.id) "${p.name} · ACTIVE" else p.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${p.owner.ifBlank { "No owner" }}/${p.repo.ifBlank { "No repository" }} · ${p.branch}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { if (p.id != project.id) activateProfile(p); showProjectManager = false; tab = 2 }) { Text("Edit") }
                                        TextButton(onClick = { try { cloneProject(p); showProjectManager = false } catch (e: Exception) { status = "Clone failed: ${e.message}" } }) { Text("Clone") }
                                        TextButton(enabled = profiles.size > 1, onClick = { deleteTarget = p }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProjectManager = false }) { Text("Close") } }
        )
    }

    if (showCreateProject) {
        AlertDialog(
            onDismissRequest = { showCreateProject = false },
            title = { Text("Create project") },
            text = { OutlinedTextField(newProjectName, { newProjectName = it }, label = { Text("Project name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                try { createProject(newProjectName); showCreateProject = false; showProjectManager = false }
                catch (e: Exception) { status = "Could not create project: ${e.message}" }
            }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCreateProject = false }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This permanently deletes this project's local source files, chat history and saved credentials from this device. The GitHub repository itself will NOT be deleted. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { removeProject(target); deleteTarget = null }) { Text("Delete project") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

private fun loadHistory(prefs: android.content.SharedPreferences, projectId: String): List<ChatMessage> {
    val raw = prefs.getString("chat_history_$projectId", null)
    if (raw.isNullOrBlank()) return listOf(ChatMessage("assistant", "Welcome to Talk2APK. Describe the Android app you want to create or change."))
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { ChatMessage(it.getString("role"), it.getString("content")) } }
    } catch (_: Exception) { emptyList() }
}
