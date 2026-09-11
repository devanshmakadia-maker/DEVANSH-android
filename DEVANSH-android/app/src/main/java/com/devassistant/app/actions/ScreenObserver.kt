package com.devassistant.app.actions

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Walks the current accessibility node tree and produces a compact, numbered list of
 * interactable elements (buttons, text fields, list items, links, and unlabeled tappable items
 * like photo thumbnails) that the AI agent can refer to by index, plus the package name of the
 * foreground app. This is the "OBSERVE" step.
 */
object ScreenObserver {

    data class ScreenElement(
        val index: Int,
        val text: String,
        val className: String,
        val clickable: Boolean,
        val editable: Boolean,
        val node: AccessibilityNodeInfo
    )

    fun describeScreen(root: AccessibilityNodeInfo?, packageName: String?): Pair<String, List<ScreenElement>> {
        val elements = mutableListOf<ScreenElement>()
        if (root != null) collect(root, elements)

        val arr = JSONArray()
        elements.forEach { el ->
            arr.put(
                JSONObject().apply {
                    put("index", el.index)
                    put("text", el.text)
                    put("type", el.className)
                    put("clickable", el.clickable)
                    put("editable", el.editable)
                }
            )
        }

        val summary = JSONObject().apply {
            put("foreground_app", packageName ?: "unknown")
            put("elements", arr)
        }.toString()

        return summary to elements
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<ScreenElement>) {
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
        val isInteractive = node.isClickable || node.isEditable || node.isCheckable || node.isLongClickable
        if (isInteractive) {
            // Unlabeled but tappable items (e.g. photo/video thumbnails in a gallery grid) are
            // still included so the agent can refer to "the first item", "the third photo", etc.
            val displayText = when {
                text.isNotEmpty() -> text
                node.isEditable -> "(text field)"
                node.className?.toString()?.contains("Image", ignoreCase = true) == true -> "(image, unlabeled)"
                else -> "(unlabeled tappable item)"
            }
            out.add(
                ScreenElement(
                    index = out.size,
                    text = displayText,
                    className = node.className?.toString() ?: "unknown",
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    node = node
                )
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out)
        }
    }
}
