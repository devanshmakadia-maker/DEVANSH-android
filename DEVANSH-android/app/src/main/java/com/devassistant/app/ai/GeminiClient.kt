package com.devassistant.app.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around Google's Gemini API (generativelanguage.googleapis.com) with function
 * calling, used as a free-tier alternative to the Anthropic client. The user supplies their own
 * free Google AI Studio API key in Settings; it is stored locally via SettingsStore and sent
 * only to Google's API.
 *
 * This class translates to/from the same Anthropic-style JSON shape the rest of the app (AiAgent)
 * already speaks, so AiAgent.kt itself does not need to change.
 */
class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // "gemini-2.0-flash" was decommissioned by Google in 2026 and now returns 404, which is
    // why saved keys looked "broken" even though they were valid. "gemini-flash-latest" is a
    // Google-maintained alias that auto-points at whatever the current free-tier Flash model is,
    // so this stops going stale every time Google retires a dated model id.
    private val model = "gemini-flash-latest"

    fun sendMessage(systemPrompt: String, messages: JSONArray, tools: JSONArray): JSONObject {
        if (apiKey.isBlank()) {
            throw RuntimeException("No Gemini API key is saved yet. Add one in Settings.")
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            put("contents", toGeminiContents(messages))
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toGeminiTools(tools))))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException(readableError(response.code, text))
            }
            return toAnthropicShape(JSONObject(text))
        }
    }

    /** Turns Google's raw error JSON into a short, human-readable message instead of a JSON dump. */
    private fun readableError(code: Int, rawBody: String): String {
        val googleMessage = try {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
        return when (code) {
            400 -> "Gemini rejected the request (${googleMessage ?: "bad request"}). Your API key may be malformed — re-copy it from aistudio.google.com."
            403 -> "Gemini refused this API key (${googleMessage ?: "permission denied"}). Check the key is enabled for the Generative Language API."
            404 -> "Gemini model not found (${googleMessage ?: "the model id may have been retired"}). This usually means Google renamed/retired the model — try again later or update the model id in GeminiClient.kt."
            429 -> "Gemini's free-tier rate limit was hit (${googleMessage ?: "too many requests"}). Wait a bit and try again."
            else -> "Gemini API error $code: ${googleMessage ?: rawBody.take(200)}"
        }
    }

    // ---- messages: Anthropic-style turns -> Gemini "contents" ----
    private fun toGeminiContents(messages: JSONArray): JSONArray {
        val contents = JSONArray()
        for (i in 0 until messages.length()) {
            val turn = messages.getJSONObject(i)
            val role = turn.getString("role") // "user" or "assistant"
            val geminiRole = if (role == "assistant") "model" else "user"
            val parts = JSONArray()
            val content = turn.get("content")

            if (content is String) {
                parts.put(JSONObject().put("text", content))
            } else {
                val blocks = content as JSONArray
                for (j in 0 until blocks.length()) {
                    val block = blocks.getJSONObject(j)
                    when (block.optString("type")) {
                        "text" -> parts.put(JSONObject().put("text", block.optString("text")))
                        "tool_use" -> parts.put(
                            JSONObject().put(
                                "functionCall",
                                JSONObject().put("name", block.optString("name"))
                                    .put("args", block.optJSONObject("input") ?: JSONObject())
                            )
                        )
                        "tool_result" -> parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", block.optString("tool_use_id"))
                                    .put("response", JSONObject().put("result", block.optString("content")))
                            )
                        )
                    }
                }
            }
            contents.put(JSONObject().put("role", geminiRole).put("parts", parts))
        }
        return contents
    }

    // ---- tools: Anthropic-style input_schema -> Gemini "parameters" ----
    private fun toGeminiTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            out.put(
                JSONObject()
                    .put("name", t.getString("name"))
                    .put("description", t.optString("description"))
                    .put("parameters", t.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))
            )
        }
        return out
    }

    // ---- Gemini response -> Anthropic-style {"content": [...]} so AiAgent.kt is unchanged ----
    private fun toAnthropicShape(gemini: JSONObject): JSONObject {
        val content = JSONArray()
        val candidates = gemini.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
        val parts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text")) {
                content.put(JSONObject().put("type", "text").put("text", part.getString("text")))
            } else if (part.has("functionCall")) {
                val fc = part.getJSONObject("functionCall")
                content.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", "call_${UUID.randomUUID()}")
                        .put("name", fc.getString("name"))
                        .put("input", fc.optJSONObject("args") ?: JSONObject())
                )
            }
        }
        return JSONObject().put("content", content)
    }
}
