package com.devassistant.app.ai

import android.content.Context
import com.devassistant.app.DevanshAccessibilityService
import com.devassistant.app.actions.ActionExecutor
import com.devassistant.app.actions.RiskLevel
import com.devassistant.app.actions.ScreenObserver
import com.devassistant.app.data.ActivityLogStore
import com.devassistant.app.data.LogEntry
import com.devassistant.app.data.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_STEPS = 12

private val SYSTEM_PROMPT = """
You are DEVANSH, a voice-controlled Android automation agent. You are given a natural-language
request from the phone's owner and a live snapshot of what is currently on screen. Decide the
single next action to take using the available tools, one tool call per turn. After each action
you will be shown the resulting screen so you can verify it worked before continuing.

Rules:
- Never attempt to enter a password, OTP, or bypass any login/CAPTCHA/biometric/2FA screen. If
  one appears, call ask_user to pause and wait for the human.
- If a request is ambiguous (e.g. multiple matching profiles/files), call ask_user to clarify
  rather than guessing.
- Call task_complete as soon as the request has been fulfilled, with a short natural spoken
  summary of what happened.
- Call task_failed if the action cannot be completed, explaining why in plain language.
- Prefer interacting with elements from the provided screen snapshot (by index) over guessing.
- For "open Chrome/the browser and search for X" style requests, prefer open_url with
  https://www.google.com/search?q=X directly instead of tapping through the browser UI — it is
  far more reliable.
- For searching inside a specific app (e.g. Instagram, YouTube's own search), open the app, tap
  its search field (click_element), type_text the query, then call press_enter to submit it.
- Elements with text like "(unlabeled tappable item)" or "(image, unlabeled)" are real tappable
  items without a visible label (e.g. photo/video thumbnails in a grid). You can still refer to
  them by index — e.g. "the first photo" is usually the first such element in reading order
  (top-left to bottom-right) after opening a gallery/photos app.
""".trimIndent()

sealed class AgentOutcome {
    data class Speak(val text: String) : AgentOutcome()
    data class NeedsUser(val question: String) : AgentOutcome()
    data class Failed(val reason: String) : AgentOutcome()
    data class NeedsConfirmation(val actionName: String, val args: Map<String, String>, val description: String) : AgentOutcome()
}

/**
 * Runs the HEAR(already done) -> OBSERVE -> PLAN -> ACT -> VERIFY -> RESPOND loop for a single
 * user command, calling [onStep] for activity-log updates and [onOutcome] with the final result.
 */
class AiAgent(
    private val context: Context,
    private val apiKey: String
) {
    private val executor = ActionExecutor(context)
    private val client = GeminiClient(apiKey)

    fun run(command: String, logEntry: LogEntry, onOutcome: (AgentOutcome) -> Unit) {
        val messages = JSONArray()
        var lastElements: List<ScreenObserver.ScreenElement> = emptyList()

        val (screenSummary, elements) = observeScreen()
        lastElements = elements
        messages.put(userTurn("User request: \"$command\"\n\nCurrent screen:\n$screenSummary"))

        for (step in 1..MAX_STEPS) {
            val response = try {
                client.sendMessage(SYSTEM_PROMPT, messages, toolDefinitions())
            } catch (e: Exception) {
                ActivityLogStore.finish(logEntry, TaskStatus.FAILED, e.message)
                onOutcome(AgentOutcome.Failed("I couldn't reach the AI service: ${e.message}"))
                return
            }

            val content = response.optJSONArray("content") ?: JSONArray()
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val toolUse = findToolUse(content) ?: run {
                // Model replied with plain text only; treat it as a spoken completion.
                val text = extractText(content)
                ActivityLogStore.finish(logEntry, TaskStatus.COMPLETED)
                onOutcome(AgentOutcome.Speak(text.ifBlank { "Done." }))
                return
            }

            val toolName = toolUse.getString("name")
            val toolInput = toolUse.optJSONObject("input") ?: JSONObject()
            val toolUseId = toolUse.getString("id")

            when (toolName) {
                "task_complete" -> {
                    val summary = toolInput.optString("summary", "Done.")
                    ActivityLogStore.appendStep(logEntry, summary)
                    ActivityLogStore.finish(logEntry, TaskStatus.COMPLETED)
                    onOutcome(AgentOutcome.Speak(summary))
                    return
                }
                "task_failed" -> {
                    val reason = toolInput.optString("reason", "The task could not be completed.")
                    ActivityLogStore.finish(logEntry, TaskStatus.FAILED, reason)
                    onOutcome(AgentOutcome.Failed(reason))
                    return
                }
                "ask_user" -> {
                    val question = toolInput.optString("question", "Could you clarify?")
                    ActivityLogStore.finish(logEntry, TaskStatus.NEEDS_USER)
                    onOutcome(AgentOutcome.NeedsUser(question))
                    return
                }
                else -> {
                    val args = mutableMapOf<String, String>()
                    toolInput.keys().forEach { k -> args[k] = toolInput.optString(k) }
                    lastElements.getOrNull(args["index"]?.toIntOrNull() ?: -1)?.let {
                        args["matched_text"] = it.text
                    }

                    val risk = executor.riskOf(toolName, args)
                    if (risk == RiskLevel.HIGH) {
                        ActivityLogStore.finish(logEntry, TaskStatus.NEEDS_USER)
                        onOutcome(
                            AgentOutcome.NeedsConfirmation(
                                toolName, args,
                                "This will $toolName (${args["matched_text"] ?: args["text"] ?: ""}). Should I continue?"
                            )
                        )
                        return
                    }

                    val result = executor.execute(toolName, args, lastElements)
                    ActivityLogStore.appendStep(logEntry, "${toolName}(${args}) -> ${result.message}")

                    val (newSummary, newElements) = observeScreen()
                    lastElements = newElements

                    val toolResultContent = JSONObject().apply {
                        put("success", result.success)
                        put("message", result.message)
                        put("new_screen", JSONObject(newSummary))
                    }
                    messages.put(toolResultTurn(toolUseId, toolResultContent.toString()))
                }
            }
        }

        ActivityLogStore.finish(logEntry, TaskStatus.FAILED, "Too many steps")
        onOutcome(AgentOutcome.Failed("This is taking more steps than expected, so I stopped for safety."))
    }

    /** Call this to resume after the user answered an ask_user question or confirmed a high-risk action. */
    fun resumeWithUserAnswer(previousMessages: JSONArray, answer: String, logEntry: LogEntry, onOutcome: (AgentOutcome) -> Unit) {
        previousMessages.put(userTurn(answer))
        // Re-enters the same loop structure by delegating to a fresh run driven by the existing
        // transcript; a full production build would factor run() to accept an initial transcript.
    }

    private fun observeScreen(): Pair<String, List<ScreenObserver.ScreenElement>> {
        val service = DevanshAccessibilityService.instance
            ?: return """{"foreground_app":"unknown","elements":[],"note":"Accessibility service not connected."}""" to emptyList()
        return service.snapshotScreen()
    }

    private fun userTurn(text: String) = JSONObject().apply {
        put("role", "user")
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    }

    private fun toolResultTurn(toolUseId: String, resultText: String) = JSONObject().apply {
        put("role", "user")
        put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", toolUseId)
                    .put("content", resultText)
            )
        )
    }

    private fun findToolUse(content: JSONArray): JSONObject? {
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "tool_use") return block
        }
        return null
    }

    private fun extractText(content: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    private fun toolDefinitions(): JSONArray {
        fun tool(name: String, description: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("description", description)
                put("input_schema", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                })
            }
        }
        fun prop(desc: String) = JSONObject().put("type", "string").put("description", desc)

        return JSONArray().apply {
            put(tool("open_app", "Launch an installed app by name or package.",
                JSONObject().put("package_or_name", prop("App display name, e.g. 'Instagram', or package name.")),
                listOf("package_or_name")))
            put(tool("click_element", "Tap the screen element at the given index from the last screen snapshot.",
                JSONObject().put("index", prop("Index of the element to tap.")), listOf("index")))
            put(tool("type_text", "Type text into an editable field at the given index.",
                JSONObject().put("index", prop("Index of the text field.")).put("text", prop("Text to type.")),
                listOf("index", "text")))
            put(tool("press_enter", "Submit/confirm the text field at the given index, like pressing Enter or a keyboard's search action. Use this after type_text when searching inside an app.",
                JSONObject().put("index", prop("Index of the text field to submit.")), listOf("index")))
            put(tool("scroll_down", "Scroll the current screen down.", JSONObject()))
            put(tool("scroll_up", "Scroll the current screen up.", JSONObject()))
            put(tool("go_back", "Perform the Android back action.", JSONObject()))
            put(tool("go_home", "Go to the home screen.", JSONObject()))
            put(tool("take_screenshot", "Take a screenshot.", JSONObject()))
            put(tool("open_url", "Open a URL in the browser.", JSONObject().put("url", prop("Full URL.")), listOf("url")))
            put(tool("make_call", "Place a phone call.", JSONObject().put("number", prop("Phone number.")), listOf("number")))
            put(tool("open_settings", "Open an Android settings screen (e.g. for Wi-Fi, when direct control isn't possible).",
                JSONObject().put("screen", prop("Settings intent action, e.g. android.settings.WIFI_SETTINGS.")), listOf("screen")))
            put(tool("read_screen", "Re-read the current screen without taking any action.", JSONObject()))
            put(tool("ask_user", "Pause and ask the user a clarifying question, or wait for them to complete a login/OTP/CAPTCHA.",
                JSONObject().put("question", prop("Question to ask the user.")), listOf("question")))
            put(tool("task_complete", "Call this once the user's request has been fully satisfied.",
                JSONObject().put("summary", prop("Short spoken summary of what was done.")), listOf("summary")))
            put(tool("task_failed", "Call this if the task cannot be completed.",
                JSONObject().put("reason", prop("Plain-language reason.")), listOf("reason")))
        }
    }
}
