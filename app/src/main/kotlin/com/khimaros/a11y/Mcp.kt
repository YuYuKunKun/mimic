package com.khimaros.a11y

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// a minimal in-app mcp server over streamable http (json-rpc 2.0). it exposes the
// view/interact commands as mcp tools backed by the same Commands core. each POST
// is handled statelessly and answered with application/json; notifications get a
// 202 with no body. enough for request/response tool use by an mcp client.
object Mcp {

    // returns (http status line, response body).
    fun handle(ctx: Context, body: String): Pair<String, String> {
        val req = try {
            JSONObject(body)
        } catch (_: Exception) {
            return "200 OK" to rpcError(JSONObject.NULL, -32700, "parse error")
        }
        val id = if (req.has("id") && !req.isNull("id")) req.get("id") else null
        return when (val method = req.optString("method")) {
            "initialize" -> "200 OK" to rpcResult(id, initializeResult(req.optJSONObject("params")))
            "ping" -> "200 OK" to rpcResult(id, JSONObject())
            "tools/list" -> "200 OK" to rpcResult(id, JSONObject().put("tools", TOOLS))
            "tools/call" -> "200 OK" to rpcResult(id, callTool(ctx, req.optJSONObject("params")))
            "" -> "400 Bad Request" to rpcError(id ?: JSONObject.NULL, -32600, "invalid request")
            else ->
                // notifications (no id) need no response; other unknowns are errors.
                if (id == null) "202 Accepted" to ""
                else "200 OK" to rpcError(id, -32601, "method not found: $method")
        }
    }

    private fun initializeResult(params: JSONObject?): JSONObject {
        val version = params?.optString("protocolVersion").takeIf { !it.isNullOrEmpty() } ?: Host.MCP_PROTOCOL
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", Host.SERVER_NAME).put("version", Host.VERSION))
    }

    private fun callTool(ctx: Context, params: JSONObject?): JSONObject {
        val name = params?.optString("name") ?: ""
        val action = TOOL_ACTION[name]
            ?: return toolResult("unknown tool: $name", isError = true)
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        val get = { k: String -> if (args.has(k) && !args.isNull(k)) args.get(k).toString() else null }
        val result = Commands.run(ctx, action, get)
        val text = when {
            !result.ok -> result.error ?: "error"
            result.data is String -> result.data
            result.data != null -> result.data.toString()
            else -> "ok"
        }
        return toolResult(text, isError = !result.ok)
    }

    private fun toolResult(text: String, isError: Boolean): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    private fun rpcResult(id: Any?, result: Any): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)
        .toString()

    private fun rpcError(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    // ---- tool catalog ----

    private val TOOL_ACTION = mapOf(
        "a11y_status" to Cmd.STATUS,
        "a11y_dump" to Cmd.DUMP,
        "a11y_find" to Cmd.FIND,
        "a11y_tap" to Cmd.TAP,
        "a11y_long_press" to Cmd.LONG_PRESS,
        "a11y_swipe" to Cmd.SWIPE,
        "a11y_click" to Cmd.CLICK,
        "a11y_set_text" to Cmd.SET_TEXT,
        "a11y_global" to Cmd.GLOBAL,
        "a11y_launch" to Cmd.LAUNCH,
    )

    private fun prop(type: String, desc: String, enum: List<String>? = null): JSONObject {
        val o = JSONObject().put("type", type).put("description", desc)
        enum?.let { o.put("enum", JSONArray(it)) }
        return o
    }

    private fun schema(required: List<String>, props: Map<String, JSONObject>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "object").put("properties", p).also {
            if (required.isNotEmpty()) it.put("required", JSONArray(required))
        }
    }

    private fun tool(name: String, description: String, schema: JSONObject): JSONObject =
        JSONObject().put("name", name).put("description", description).put("inputSchema", schema)

    private val FILTER = prop("string", "interactive | text | visible | all", listOf("interactive", "text", "visible", "all"))
    private val FORMAT = prop("string", "tree | flat | compact", listOf("tree", "flat", "compact"))
    private val BY = prop("string", "text | id | class | desc", listOf("text", "id", "class", "desc"))
    private val MATCH = prop("string", "exact | contains | regex", listOf("exact", "contains", "regex"))
    private val DEPTH = prop("integer", "max tree depth (-1 = unlimited)")
    private val PACKAGE = prop("string", "restrict to one app package")
    private val FIELDS = prop("string", "comma list: class,text,desc,id,bounds,center,actions")

    private val TOOLS: JSONArray = JSONArray(listOf(
        tool("a11y_status", "report whether the service is enabled and which surfaces are on", schema(emptyList(), emptyMap())),
        tool("a11y_dump", "dump the active window node tree; filter on-device to keep output small",
            schema(emptyList(), mapOf("filter" to FILTER, "format" to FORMAT, "max_depth" to DEPTH, "package" to PACKAGE, "fields" to FIELDS))),
        tool("a11y_find", "find nodes matching a query; returns a flat match list by default",
            schema(listOf("query"), mapOf(
                "query" to prop("string", "the text/id/class/desc to match"),
                "by" to BY, "match" to MATCH, "format" to FORMAT, "fields" to FIELDS, "filter" to FILTER, "max_depth" to DEPTH, "package" to PACKAGE))),
        tool("a11y_tap", "tap at screen coordinates",
            schema(listOf("x", "y"), mapOf("x" to prop("integer", "x px"), "y" to prop("integer", "y px"), "duration" to prop("integer", "ms")))),
        tool("a11y_long_press", "long press at screen coordinates",
            schema(listOf("x", "y"), mapOf("x" to prop("integer", "x px"), "y" to prop("integer", "y px"), "duration" to prop("integer", "ms")))),
        tool("a11y_swipe", "swipe from (x,y) to (x2,y2)",
            schema(listOf("x", "y", "x2", "y2"), mapOf(
                "x" to prop("integer", "start x"), "y" to prop("integer", "start y"),
                "x2" to prop("integer", "end x"), "y2" to prop("integer", "end y"), "duration" to prop("integer", "ms")))),
        tool("a11y_click", "click a node found by text/id/class/desc, or tap coordinates (by=coords)",
            schema(emptyList(), mapOf(
                "by" to prop("string", "coords | text | id | class | desc", listOf("coords", "text", "id", "class", "desc")),
                "query" to prop("string", "value to match when by is not coords"),
                "x" to prop("integer", "x px when by=coords"), "y" to prop("integer", "y px when by=coords"), "match" to MATCH))),
        tool("a11y_set_text", "set the text of an editable node located by text/id/class/desc",
            schema(listOf("text"), mapOf("text" to prop("string", "text to enter"), "by" to BY, "query" to prop("string", "value to match"), "match" to MATCH))),
        tool("a11y_global", "perform a global navigation action",
            schema(listOf("nav"), mapOf("nav" to prop("string", "back | home | recents | notifications", listOf("back", "home", "recents", "notifications"))))),
        tool("a11y_launch", "launch an app or activity (by package, component, or action/uri)",
            schema(emptyList(), mapOf(
                "package" to prop("string", "launch this app's main activity"),
                "component" to prop("string", "explicit 'pkg/.Activity'"),
                "action" to prop("string", "an intent action"),
                "uri" to prop("string", "data uri (with action, or opened via ACTION_VIEW)")))),
    ))
}
