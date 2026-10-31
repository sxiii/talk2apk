package dev.talk2apk

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val content: String)
data class AiReply(val text: String, val changes: List<FileChange> = emptyList())

class AiClient(private val baseUrl: String, private val apiKey: String, private val model: String) {
    private fun request(endpoint: String, body: JSONObject): Pair<Int, String> {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 25_000; readTimeout = 180_000; doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to raw
        } finally { c.disconnect() }
    }

    fun send(system: String, history: List<ChatMessage>, projectContext: String): AiReply {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "AI endpoint must use HTTPS" }
        require(apiKey.isNotBlank() && model.isNotBlank()) { "AI API key and model are required" }
        // Always append the current built-in policy after any saved/custom prompt. This upgrades
        // existing installs too, even if they still have an older system prompt stored locally.
        val enforcedSystem = "$system\n\nMANDATORY CURRENT TALK2APK POLICY:\n${AiFileProtocol.systemPrompt}\n\n$projectContext"
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", enforcedSystem))
        history.takeLast(24).forEach { m -> msgs.put(JSONObject().put("role", m.role).put("content", m.content)) }
        val endpoint = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        val toolBody = JSONObject().put("model", model).put("messages", msgs)
            .put("tools", JSONArray().put(AiFileProtocol.toolDefinition()))
            .put("tool_choice", "auto").put("temperature", 0.2)
        var (code, raw) = request(endpoint, toolBody)
        // Some OpenAI-compatible gateways implement chat completions but not function calling.
        // Retry without tools; the system prompt asks for the same JSON file protocol.
        if (code == 400 || code == 422) {
            val fallbackBody = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.2)
            val retry = request(endpoint, fallbackBody)
            code = retry.first; raw = retry.second
        }
        if (code !in 200..299) throw IllegalStateException("AI API HTTP $code: ${raw.take(800)}")
        val msg = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val toolCalls = msg.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val fn = toolCalls.getJSONObject(i).optJSONObject("function") ?: continue
                if (fn.optString("name") == "apply_file_changes") {
                    val files = AiFileProtocol.parseFiles(JSONObject(fn.getString("arguments")))
                    return AiReply("Prepared ${files.size} file operation(s). Review the summary below, then publish when ready.", files)
                }
            }
        }
        val text = msg.optString("content", "")
        val fallback = AiFileProtocol.parseFallback(text)
        return if (fallback != null) AiReply("Prepared ${fallback.size} file operation(s) from structured JSON. Review before publishing.", fallback)
        else AiReply(text.ifBlank { "The model returned an empty response." })
    }
}
