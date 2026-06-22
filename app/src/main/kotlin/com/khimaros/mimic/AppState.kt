package com.khimaros.mimic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

// per-surface on/off state, persisted across reboots. three independent surfaces
// expose the same MimicService core:
//   intents -- the broadcast receiver (toggled by enabling/disabling the
//              CommandReceiver component, so a stopped surface receives nothing).
//   http    -- the rest endpoint on the localhost HostService.
//   mcp     -- the mcp endpoint on the same HostService.
// http and mcp share one foreground service, which runs while either is on.
object AppState {
    private const val PREFS = "state"
    private const val KEY_INTENTS = "intents"
    private const val KEY_HTTP = "http"
    private const val KEY_MCP = "mcp"
    private const val KEY_BOOT = "start_on_boot"
    private const val KEY_BIND = "bind_address"

    fun intents(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_INTENTS, false)
    fun http(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HTTP, false)
    fun mcp(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MCP, false)
    fun startOnBoot(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BOOT, false)

    // the interface the http/mcp server binds; loopback by default.
    fun bindAddress(ctx: Context): String = prefs(ctx).getString(KEY_BIND, Net.LOOPBACK) ?: Net.LOOPBACK

    // change the bind interface and re-apply: the running server rebinds because
    // onStartCommand notices the address differs.
    fun setBindAddress(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_BIND, value).apply()
        applyHost(ctx)
    }

    fun setStartOnBoot(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_BOOT, value).apply()

    // the intents surface is gated at the component level: disabling the receiver
    // removes the intent endpoint entirely rather than relying on a runtime flag.
    fun setIntents(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_INTENTS, value).apply()
        val state = if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ctx.applicationContext.packageManager.setComponentEnabledSetting(
            ComponentName(ctx.applicationContext, CommandReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun setHttp(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HTTP, value).apply()
        applyHost(ctx)
    }

    fun setMcp(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MCP, value).apply()
        applyHost(ctx)
    }

    // start or stop the foreground HostService to match the http/mcp prefs.
    fun applyHost(ctx: Context) {
        val app = ctx.applicationContext
        val intent = Intent(app, HostService::class.java)
        if (http(app) || mcp(app)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
        } else {
            app.stopService(intent)
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
