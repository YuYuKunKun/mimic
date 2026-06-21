package com.khimaros.mimic

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

// how a view request is shaped. parsed once from intent extras so the traversal
// logic stays free of intent plumbing.
data class ViewConfig(
    val format: String,
    val filter: String,
    val maxDepth: Int,
    val pkg: String?,
    val fields: Set<String>,
    val by: String?,
    val query: String?,
    val match: String,
) {
    companion object {
        fun from(get: (String) -> String?): ViewConfig {
            val rawFields = get(Extras.FIELDS)
            val fields = if (rawFields.isNullOrBlank()) NODE_FIELDS.toSet()
            else rawFields.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return ViewConfig(
                format = get(Extras.FORMAT) ?: Defaults.FORMAT,
                filter = get(Extras.FILTER) ?: Defaults.FILTER,
                maxDepth = get(Extras.MAX_DEPTH)?.toIntOrNull() ?: Defaults.MAX_DEPTH,
                pkg = get(Extras.PACKAGE)?.ifBlank { null },
                fields = fields,
                by = get(Extras.BY)?.ifBlank { null },
                query = get(Extras.QUERY),
                match = get(Extras.MATCH) ?: Defaults.MATCH,
            )
        }
    }
}

// serializes an AccessibilityNodeInfo tree, applying the filter, query, depth,
// and package constraints on the device so the returned payload stays small.
// all traversal is pure with respect to the node tree (read-only).
object NodeTree {

    // DUMP: render the (filtered) tree in the requested format. returns either a
    // json value (tree/flat) or a string (compact).
    fun render(root: AccessibilityNodeInfo?, cfg: ViewConfig): Any = when (cfg.format) {
        "compact" -> collect(root, cfg).joinToString("\n") { compactLine(it) }
        "flat" -> JSONArray().apply { collect(root, cfg).forEach { put(nodeJson(it, cfg.fields)) } }
        else -> treeJson(root, cfg, 0) ?: JSONObject()
    }

    // FIND / CLICK / SET_TEXT target resolution: nodes matching filter+query in
    // pre-order. interaction takes the first.
    fun collect(root: AccessibilityNodeInfo?, cfg: ViewConfig): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (root != null) walk(root, cfg, 0) { out.add(it) }
        return out
    }

    fun firstMatch(root: AccessibilityNodeInfo?, cfg: ViewConfig): AccessibilityNodeInfo? =
        collect(root, cfg).firstOrNull()

    private fun walk(
        node: AccessibilityNodeInfo,
        cfg: ViewConfig,
        depth: Int,
        emit: (AccessibilityNodeInfo) -> Unit,
    ) {
        if (selected(node, cfg)) emit(node)
        if (cfg.maxDepth < 0 || depth < cfg.maxDepth) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, cfg, depth + 1, emit) }
            }
        }
    }

    // a node survives into a tree render if it is selected itself or has any
    // selected descendant, so container structure around matches is preserved.
    private fun treeJson(node: AccessibilityNodeInfo?, cfg: ViewConfig, depth: Int): JSONObject? {
        if (node == null) return null
        val children = JSONArray()
        if (cfg.maxDepth < 0 || depth < cfg.maxDepth) {
            for (i in 0 until node.childCount) {
                treeJson(node.getChild(i), cfg, depth + 1)?.let { children.put(it) }
            }
        }
        if (!selected(node, cfg) && children.length() == 0) return null
        return nodeJson(node, cfg.fields).also {
            if ("children" in cfg.fields && children.length() > 0) it.put("children", children)
        }
    }

    private fun selected(node: AccessibilityNodeInfo, cfg: ViewConfig): Boolean =
        passesFilter(node, cfg) && matchesQuery(node, cfg)

    private fun passesFilter(node: AccessibilityNodeInfo, cfg: ViewConfig): Boolean {
        cfg.pkg?.let { if (node.packageName?.toString() != it) return false }
        return when (cfg.filter) {
            "interactive" -> node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isFocusable || node.isCheckable
            "text" -> textOf(node) != null || descOf(node) != null
            "visible" -> node.isVisibleToUser
            else -> true
        }
    }

    private fun matchesQuery(node: AccessibilityNodeInfo, cfg: ViewConfig): Boolean {
        val q = cfg.query ?: return true
        val hay = when (cfg.by ?: "text") {
            "id" -> node.viewIdResourceName
            "class" -> node.className?.toString()
            "desc" -> descOf(node)
            else -> textOf(node)
        } ?: return false
        return when (cfg.match) {
            "exact" -> hay == q
            "regex" -> runCatching { Regex(q).containsMatchIn(hay) }.getOrDefault(false)
            else -> hay.contains(q, ignoreCase = true)
        }
    }

    private fun nodeJson(node: AccessibilityNodeInfo, fields: Set<String>): JSONObject {
        val o = JSONObject()
        val r = Rect().also { node.getBoundsInScreen(it) }
        if ("class" in fields) node.className?.let { o.put("class", it.toString()) }
        if ("text" in fields) textOf(node)?.let { o.put("text", it) }
        if ("desc" in fields) descOf(node)?.let { o.put("desc", it) }
        if ("id" in fields) node.viewIdResourceName?.let { o.put("id", it) }
        if ("bounds" in fields) o.put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
        if ("center" in fields) o.put("center", JSONArray(listOf(r.centerX(), r.centerY())))
        if ("actions" in fields) o.put("actions", JSONArray(actionsOf(node)))
        return o
    }

    // one terse line per node: "cx,cy<tab>class<tab>label<tab>id". built for llm
    // context budgets; everything needed to click is on the line.
    private fun compactLine(node: AccessibilityNodeInfo): String {
        val r = Rect().also { node.getBoundsInScreen(it) }
        // collapse tabs/newlines in the label so each node stays on one
        // tab-delimited line regardless of its text content.
        val label = (textOf(node) ?: descOf(node) ?: "").replace(WHITESPACE, " ").trim()
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        return "${r.centerX()},${r.centerY()}\t$cls\t$label\t$id"
    }

    private val WHITESPACE = Regex("[\\t\\r\\n]+")

    private fun textOf(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotEmpty() }

    private fun descOf(node: AccessibilityNodeInfo): String? =
        node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }

    private fun actionsOf(node: AccessibilityNodeInfo): List<String> = buildList {
        if (node.isClickable) add("click")
        if (node.isLongClickable) add("long")
        if (node.isEditable) add("edit")
        if (node.isScrollable) add("scroll")
        if (node.isFocusable) add("focus")
        if (node.isCheckable) add("check")
        if (node.isChecked) add("checked")
        if (node.isSelected) add("selected")
    }
}
