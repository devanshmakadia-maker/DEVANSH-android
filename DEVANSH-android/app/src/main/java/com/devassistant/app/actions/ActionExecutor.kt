package com.devassistant.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.devassistant.app.DevanshAccessibilityService

enum class RiskLevel { LOW, HIGH }

data class ActionResult(val success: Boolean, val message: String)

/**
 * Executes a single planned action. High-risk actions are flagged so the caller (AiAgent) can
 * pause and ask the user for confirmation before calling execute().
 */
class ActionExecutor(private val context: Context) {

    fun riskOf(actionName: String, args: Map<String, String>): RiskLevel {
        return when (actionName) {
            "delete_file", "send_message", "make_call", "open_app_settings_for_uninstall",
            "install_app", "change_security_setting" -> RiskLevel.HIGH
            "click_element" -> {
                // Clicking something that looks like a purchase/payment/delete confirmation
                val t = (args["matched_text"] ?: "").lowercase()
                if (listOf("buy", "pay", "purchase", "delete", "remove", "confirm payment", "send money", "transfer")
                        .any { t.contains(it) }
                ) RiskLevel.HIGH else RiskLevel.LOW
            }
            else -> RiskLevel.LOW
        }
    }

    fun execute(actionName: String, args: Map<String, String>, lastElements: List<ScreenObserver.ScreenElement>): ActionResult {
        val service = DevanshAccessibilityService.instance
        return when (actionName) {
            "open_app" -> openApp(args["package_or_name"] ?: "")
            "go_back" -> {
                val ok = service?.goBack() ?: false
                ActionResult(ok, if (ok) "Went back." else "Accessibility service not connected.")
            }
            "go_home" -> {
                val ok = service?.goHome() ?: false
                ActionResult(ok, if (ok) "Went to home screen." else "Accessibility service not connected.")
            }
            "scroll_down" -> { service?.scroll(true); ActionResult(true, "Scrolled down.") }
            "scroll_up" -> { service?.scroll(false); ActionResult(true, "Scrolled up.") }
            "take_screenshot" -> {
                val ok = service?.takeScreenshotGlobal() ?: false
                ActionResult(ok, if (ok) "Screenshot taken." else "Could not take screenshot.")
            }
            "click_element" -> {
                val index = args["index"]?.toIntOrNull()
                val target = lastElements.getOrNull(index ?: -1)
                if (service == null) ActionResult(false, "Accessibility service not connected.")
                else if (target == null) ActionResult(false, "No such screen element.")
                else {
                    val ok = service.clickElement(target)
                    ActionResult(ok, if (ok) "Tapped '${target.text}'." else "Tap failed.")
                }
            }
            "type_text" -> {
                val index = args["index"]?.toIntOrNull()
                val text = args["text"] ?: ""
                val target = lastElements.getOrNull(index ?: -1)
                if (service == null) ActionResult(false, "Accessibility service not connected.")
                else if (target == null) ActionResult(false, "No such screen element.")
                else {
                    val ok = service.typeIntoElement(target, text)
                    ActionResult(ok, if (ok) "Typed text." else "Could not type into that field (it may be a password field).")
                }
            }
            "press_enter" -> {
                val index = args["index"]?.toIntOrNull()
                val target = lastElements.getOrNull(index ?: -1)
                if (service == null) ActionResult(false, "Accessibility service not connected.")
                else if (target == null) ActionResult(false, "No such screen element.")
                else {
                    val ok = service.pressEnter(target)
                    ActionResult(ok, if (ok) "Submitted." else "Could not submit that field.")
                }
            }
            "open_url" -> {
                val url = args["url"] ?: return ActionResult(false, "No URL given.")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult(true, "Opened $url")
                } catch (e: Exception) {
                    ActionResult(false, "Could not open URL: ${e.message}")
                }
            }
            "make_call" -> {
                val number = args["number"] ?: return ActionResult(false, "No number given.")
                try {
                    context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult(true, "Calling $number")
                } catch (e: Exception) {
                    ActionResult(false, "Could not place call: ${e.message}")
                }
            }
            "open_settings" -> {
                val screen = args["screen"] ?: Settings.ACTION_SETTINGS
                try {
                    context.startActivity(Intent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult(true, "Opened settings.")
                } catch (e: Exception) {
                    ActionResult(false, "Could not open that settings screen: ${e.message}")
                }
            }
            "read_screen" -> {
                if (service == null) ActionResult(false, "Accessibility service not connected.")
                else {
                    val (summary, _) = service.snapshotScreen()
                    ActionResult(true, summary)
                }
            }
            else -> ActionResult(false, "Unknown action: $actionName")
        }
    }

    private fun openApp(packageOrName: String): ActionResult {
        val pm = context.packageManager
        // Try as an exact package name first.
        pm.getLaunchIntentForPackage(packageOrName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
            return ActionResult(true, "Opened $packageOrName")
        }
        // Fall back to matching an installed app's display label.
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(packageOrName, ignoreCase = true)
        } ?: apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(packageOrName, ignoreCase = true)
        }
        if (match != null) {
            pm.getLaunchIntentForPackage(match.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return ActionResult(true, "Opened ${pm.getApplicationLabel(match)}")
            }
        }
        return ActionResult(false, "I couldn't find an app called '$packageOrName' installed on this phone.")
    }
}
