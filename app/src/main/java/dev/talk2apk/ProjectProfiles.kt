package dev.talk2apk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Non-secret project metadata. Credentials are stored separately through SecretStore. */
data class ProjectProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val workflow: String = "android-build.yml",
    val useLocalAi: Boolean = false,
    val localAiBase: String = "https://api.openai.com/v1",
    val localAiModel: String = "gpt-4.1-mini"
)

class ProjectProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("talk2apk_projects", Context.MODE_PRIVATE)

    fun load(): List<ProjectProfile> {
        val raw = prefs.getString("profiles", null)
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ProjectProfile(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name", "my-android-app"),
                    owner = o.optString("owner"), repo = o.optString("repo"),
                    branch = o.optString("branch", "main"),
                    workflow = o.optString("workflow", "android-build.yml"),
                    useLocalAi = o.optBoolean("useLocalAi", false),
                    localAiBase = o.optString("localAiBase", "https://api.openai.com/v1"),
                    localAiModel = o.optString("localAiModel", "gpt-4.1-mini")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun save(profiles: List<ProjectProfile>, activeId: String) {
        val a = JSONArray()
        profiles.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("owner", p.owner)
                .put("repo", p.repo).put("branch", p.branch).put("workflow", p.workflow)
                .put("useLocalAi", p.useLocalAi).put("localAiBase", p.localAiBase)
                .put("localAiModel", p.localAiModel))
        }
        prefs.edit().putString("profiles", a.toString()).putString("active_id", activeId).apply()
    }

    fun activeId(): String = prefs.getString("active_id", "").orEmpty()
}
