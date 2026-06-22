package com.khimaros.mimic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

// per-token authorization rules, stored app-private as one json array per token id
// (key = "rules:<id>"). a rule grants or denies one action class against one target
// app, or all apps ("*"). resolution prefers an exact (class, app) rule over a
// (class, *) wildcard; with no rule, the token's mode decides (allow_all -> allow,
// ask -> prompt). minting and revoking rules are gui/prompt driven, never remote.
object Permissions {
    private const val PREFS = "perms"
    private const val KEY_PREFIX = "rules:"
    const val TARGET_ANY = "*"

    private val rng = SecureRandom()

    enum class Decision { ALLOW, DENY, ASK }

    // how long a remembered prompt answer applies.
    enum class Scope { ONCE, THIS_APP, ALL_APPS }

    data class Rule(val id: String, val cls: String, val target: String, val allow: Boolean)

    fun rules(ctx: Context, tokenId: String): List<Rule> = load(ctx, tokenId)

    // resolve a decision for (token, class, target): most specific rule wins, else
    // the token mode.
    fun decision(ctx: Context, tokenId: String, mode: String, cls: String, target: String): Decision {
        val rules = load(ctx, tokenId)
        val exact = rules.firstOrNull { it.cls == cls && it.target == target }
        val wild = rules.firstOrNull { it.cls == cls && it.target == TARGET_ANY }
        val rule = exact ?: wild
        return when {
            rule != null -> if (rule.allow) Decision.ALLOW else Decision.DENY
            mode == Defaults.MODE_ALLOW_ALL -> Decision.ALLOW
            else -> Decision.ASK
        }
    }

    // persist a remembered answer, replacing any rule for the same (class, target).
    fun remember(ctx: Context, tokenId: String, cls: String, target: String, allow: Boolean) {
        val rules = load(ctx, tokenId).filterNot { it.cls == cls && it.target == target }.toMutableList()
        rules.add(Rule(shortId(), cls, target, allow))
        save(ctx, tokenId, rules)
    }

    fun revoke(ctx: Context, tokenId: String, ruleId: String): Boolean {
        val rules = load(ctx, tokenId).toMutableList()
        if (!rules.removeAll { it.id == ruleId }) return false
        save(ctx, tokenId, rules)
        return true
    }

    // drop all rules for a token (called when the token itself is revoked).
    fun clearForToken(ctx: Context, tokenId: String) =
        prefs(ctx).edit().remove(KEY_PREFIX + tokenId).apply()

    fun clearAll(ctx: Context) = prefs(ctx).edit().clear().apply()

    private fun load(ctx: Context, tokenId: String): List<Rule> {
        val raw = prefs(ctx).getString(KEY_PREFIX + tokenId, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Rule(o.getString("id"), o.getString("cls"), o.getString("target"), o.getBoolean("allow"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, tokenId: String, rules: List<Rule>) {
        if (rules.isEmpty()) return clearForToken(ctx, tokenId)
        val arr = JSONArray()
        for (r in rules) {
            arr.put(JSONObject().put("id", r.id).put("cls", r.cls).put("target", r.target).put("allow", r.allow))
        }
        prefs(ctx).edit().putString(KEY_PREFIX + tokenId, arr.toString()).apply()
    }

    private fun shortId(): String =
        ByteArray(3).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
