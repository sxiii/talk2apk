
package dev.talk2apk

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val content: String)
data class AiReply(val text: String, val changes: List<FileChange> = emptyList())

class AiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    companion object {
        private const val TAG = "Talk2ApkAiClient"

        // Leave room for JSON structure, role labels and relay-side formatting.
        private const val MAX_MESSAGE_CHARS = 11_500
        private const val MAX_CUSTOM_PROMPT_CHARS = 1_800
        private const val MAX_PROJECT_CONTEXT_CHARS = 6_000
        private const val MAX_HISTORY_MESSAGES = 24
    }

    private fun request(endpoint: String, body: JSONObject): Pair<Int, String> {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val raw = (
                if (code in 200..299) connection.inputStream
                else connection.errorStream
            )?.bufferedReader()?.use { it.readText() }.orEmpty()

            code to raw
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMessages(
        system: String,
        history: List<ChatMessage>,
        projectContext: String
    ): JSONArray {
        val policy = AiFileProtocol.systemPrompt.trim()

        // The default UI prompt is already the built-in policy.
        // Remove it before adding the mandatory current policy below.
        val customPrompt = system.trim()
            .removePrefix(policy)
            .trim()
            .take(MAX_CUSTOM_PROMPT_CHARS)

        val context = projectContext
            .take(MAX_PROJECT_CONTEXT_CHARS)

        val systemParts = mutableListOf<String>()

        if (customPrompt.isNotBlank()) {
            systemParts += "USER PROJECT INSTRUCTIONS:\n$customPrompt"
        }

        systemParts += "MANDATORY CURRENT TALK2APK POLICY:\n$policy"

        if (context.isNotBlank()) {
            systemParts += context
        }

        val systemContent = systemParts.joinToString("\n\n")

        // The system message has priority. Only the remaining budget is
        // available for conversation history.
        var remaining = (MAX_MESSAGE_CHARS - systemContent.length).coerceAtLeast(0)

        val selectedHistory = mutableListOf<ChatMessage>()

        for (message in history.takeLast(MAX_HISTORY_MESSAGES).asReversed()) {
            if (remaining <= 0) break
            if (message.content.isBlank()) continue

            val content = message.content.takeLast(remaining)
            if (content.isBlank()) continue

            selectedHistory += ChatMessage(message.role, content)
            remaining -= content.length
        }

        selectedHistory.reverse()

        val result = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemContent)
            )

        selectedHistory.forEach { message ->
            result.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }

        val totalChars = systemContent.length +
            selectedHistory.sumOf { it.content.length }

        Log.i(
            TAG,
            "Request context: total=$totalChars, " +
                "system=${systemContent.length}, " +
                "project=${context.length}, " +
                "custom=${customPrompt.length}, " +
                "history=${selectedHistory.sumOf { it.content.length }}, " +
                "messages=${selectedHistory.size}"
        )

        return result
    }

    fun send(
        system: String,
        history: List<ChatMessage>,
        projectContext: String
    ): AiReply {
        val base = baseUrl.trim().trimEnd('/')

        require(base.startsWith("https://")) {
            "AI endpoint must use HTTPS"
        }
        require(apiKey.isNotBlank() && model.isNotBlank()) {
            "AI API key and model are required"
        }

        val messages = buildMessages(system, history, projectContext)
        val endpoint = if (base.endsWith("/v1")) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }

        val toolBody = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", JSONArray().put(AiFileProtocol.toolDefinition()))
            .put("tool_choice", "auto")
            .put("temperature", 0.2)

        var (code, raw) = request(endpoint, toolBody)

        // Some OpenAI-compatible gateways implement chat completions
        // but do not support function calling.
        if (code == 400 || code == 422) {
            val fallbackBody = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.2)

            val retry = request(endpoint, fallbackBody)
            code = retry.first
            raw = retry.second
        }

        if (code !in 200..299) {
            throw IllegalStateException(
                "AI API HTTP $code: ${raw.take(800)}"
            )
        }

        val message = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        val toolCalls = message.optJSONArray("tool_calls")

        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val function = toolCalls
                    .getJSONObject(index)
                    .optJSONObject("function")
                    ?: continue

                if (function.optString("name") == "apply_file_changes") {
                    val files = AiFileProtocol.parseFiles(
                        JSONObject(function.getString("arguments"))
                    )

                    return AiReply(
                        "Prepared ${files.size} file operation(s). " +
                            "Review the summary below, then publish when ready.",
                        files
                    )
                }
            }
        }

        val text = message.optString("content", "")
        val fallback = AiFileProtocol.parseFallback(text)

        return if (fallback != null) {
            AiReply(
                "Prepared ${fallback.size} file operation(s) from structured JSON. " +
                    "Review before publishing.",
                fallback
            )
        } else {
            AiReply(text.ifBlank { "The model returned an empty response." })
        }
    }
}
