package com.khimaros.mimic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

// the surface-agnostic command core. the intent receiver, the rest endpoint, and
// the mcp endpoint all parse their own transport, then call run() with a uniform
// string-keyed getter. it returns a structured Result; each surface formats and
// transports it. authentication is handled by the transports, not here.
object Commands {

    data class Result(val ok: Boolean, val data: Any?, val error: String?) {
        // binary payloads (screenshot bytes) are base64-encoded for json consumers;
        // transports that can send bytes directly (http) check for ByteArray first.
        fun toJson(): JSONObject = JSONObject().put("ok", ok).also { o ->
            when (val d = data) {
                null -> {}
                is ByteArray -> o.put("data", Base64.encodeToString(d, Base64.NO_WRAP))
                else -> o.put("data", d)
            }
            error?.let { o.put("error", it) }
        }
    }

    private fun ok(data: Any?) = Result(true, data, null)
    private fun fail(message: String) = Result(false, null, message)

    // run a short-named command (see Cmd). `get` resolves named arguments to
    // strings (intent extras, query params, json fields). throws nothing -- bad
    // arguments come back as a failed Result.
    fun run(ctx: Context, action: String, get: (String) -> String?): Result = try {
        when (action) {
            Cmd.STATUS -> ok(status(ctx))
            Cmd.LAUNCH -> launch(ctx, get)   // launching does not need the mimic service
            else -> withService { service -> dispatch(service, action, get) }
        }
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "bad arguments")
    }

    fun status(ctx: Context): JSONObject = JSONObject()
        .put("service_enabled", MimicService.isEnabled())
        .put("paired", TokenStore.isPaired(ctx))
        .put("tokens", TokenStore.count(ctx))
        .put("pairing", TokenStore.pairingActive(System.currentTimeMillis()))
        .put("intents", AppState.intents(ctx))
        .put("http", AppState.http(ctx))
        .put("mcp", AppState.mcp(ctx))
        .put("port", Host.PORT)

    private inline fun withService(block: (MimicService) -> Result): Result {
        val service = MimicService.instance ?: return fail("accessibility service not enabled")
        return block(service)
    }

    private fun dispatch(service: MimicService, action: String, get: (String) -> String?): Result = when (action) {
        Cmd.DUMP, Cmd.FIND -> view(service, action, get)
        Cmd.TAP -> performed(service.tap(int(get, Extras.X), int(get, Extras.Y), dur(get, Defaults.TAP_DURATION_MS)))
        Cmd.LONG_PRESS -> performed(service.longPress(int(get, Extras.X), int(get, Extras.Y), dur(get, Defaults.LONG_PRESS_DURATION_MS)))
        Cmd.SWIPE -> performed(service.swipe(int(get, Extras.X), int(get, Extras.Y), int(get, Extras.X2), int(get, Extras.Y2), dur(get, Defaults.SWIPE_DURATION_MS)))
        Cmd.CLICK -> click(service, get)
        Cmd.SET_TEXT -> setText(service, get)
        Cmd.GLOBAL -> performed(service.globalNav(get(Extras.NAV) ?: ""))
        Cmd.SCREENSHOT -> screenshot(service, get)
        else -> fail("unknown command: $action")
    }

    private fun screenshot(service: MimicService, get: (String) -> String?): Result {
        val format = get(Extras.FORMAT) ?: Defaults.SCREENSHOT_FORMAT
        val quality = get(Extras.QUALITY)?.toIntOrNull() ?: Defaults.SCREENSHOT_QUALITY
        val scale = get(Extras.SCALE)?.toDoubleOrNull() ?: 1.0
        val bytes = service.captureScreenshot(format, quality, scale)
            ?: return fail("screenshot failed (unsupported, rate-limited, or capture denied)")
        return ok(bytes)
    }

    private fun view(service: MimicService, action: String, get: (String) -> String?): Result {
        val root = service.activeRoot() ?: return fail("no active window")
        var cfg = ViewConfig.from(get)
        // FIND defaults to a flat match list unless an explicit format is given.
        if (action == Cmd.FIND && get(Extras.FORMAT) == null) cfg = cfg.copy(format = "flat")
        return ok(NodeTree.render(root, cfg))
    }

    private fun click(service: MimicService, get: (String) -> String?): Result {
        val by = get(Extras.BY) ?: "coords"
        if (by == "coords") return performed(service.tap(int(get, Extras.X), int(get, Extras.Y), dur(get, Defaults.TAP_DURATION_MS)))
        val node = resolve(service, get) ?: return fail("no node matched query")
        return performed(service.clickNode(node))
    }

    // with a by/query, target the matching node; without one, target whatever node
    // currently holds input focus.
    private fun setText(service: MimicService, get: (String) -> String?): Result {
        val text = get(Extras.TEXT) ?: return fail("missing text")
        val node = if (get(Extras.QUERY).isNullOrEmpty()) {
            service.focusedInput() ?: return fail("no focused input; pass --id/--text to target a field")
        } else {
            resolve(service, get) ?: return fail("no node matched query")
        }
        return performed(service.setNodeText(node, text))
    }

    // locate the first node matching the by/query/match args in the active
    // window, fresh at call time (stateless interaction).
    private fun resolve(service: MimicService, get: (String) -> String?): AccessibilityNodeInfo? =
        if (get(Extras.QUERY).isNullOrEmpty()) null
        else NodeTree.firstMatch(service.activeRoot(), ViewConfig.from(get))

    // start an activity by package (its launcher), explicit component, or
    // action/uri. uses the mimic service context when available. note: android
    // background-activity-launch rules may block this unless the app is
    // foreground-recent; the screen must be unlocked.
    private fun launch(ctx: Context, get: (String) -> String?): Result {
        val component = get(Extras.COMPONENT)
        val action = get(Extras.ACTION)
        val uri = get(Extras.URI)
        val pkg = get(Extras.PACKAGE)
        val intent = when {
            component != null -> Intent().setComponent(
                ComponentName.unflattenFromString(component) ?: return fail("bad component: $component"))
            action != null -> Intent(action).also { i -> uri?.let { i.data = Uri.parse(it) }; pkg?.let { i.setPackage(it) } }
            uri != null -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            pkg != null -> ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: return fail("no launch intent for package: $pkg")
            else -> return fail("launch needs one of: package, component, action, uri")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (MimicService.instance ?: ctx).startActivity(intent)
            ok(JSONObject().put("launched", true))
        } catch (e: Exception) {
            fail("launch failed: ${e.message}")
        }
    }

    private fun performed(done: Boolean): Result =
        if (done) ok(JSONObject().put("performed", true)) else fail("action failed")

    private fun int(get: (String) -> String?, key: String): Int =
        get(key)?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("missing or invalid integer argument: $key")

    private fun dur(get: (String) -> String?, default: Long): Long =
        get(Extras.DURATION)?.toLongOrNull() ?: default
}
