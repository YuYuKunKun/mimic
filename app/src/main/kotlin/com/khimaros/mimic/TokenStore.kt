package com.khimaros.mimic

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

// every surface is gated by a secret token, but there is no single shared token:
// each client holds its own. tokens live in app-private shared preferences (a json
// array, unreadable by other apps without root) so individual ones can be revoked.
//
// a client obtains a token one of two ways:
//   pairing -- the user taps "start pairing" (opening a short, time-boxed window),
//              the app shows a one-time code, and the client exchanges it (over any
//              surface) for a freshly minted per-client token.
//   legacy  -- the user reveals a long-lived token in the ui and pastes it into a
//              client that cannot pair (mcp config, etc).
// minting outside pairing and all revocation are gui-only (physical access).
object TokenStore {
    private const val PREFS = "auth"
    private const val KEY_TOKENS = "tokens"
    private const val B64 = Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING

    private val rng = SecureRandom()

    // one record per issued token. `token` is the secret; `id` is a short, non-
    // secret handle used to display and revoke it.
    data class Record(
        val token: String,
        val id: String,
        val label: String,
        val kind: String,
        val created: Long,
    )

    private data class Pairing(val code: String, val expiresAt: Long, var attempts: Int)

    @Volatile
    private var pending: Pairing? = null

    fun list(ctx: Context): List<Record> = load(ctx)

    // let the ui react to token changes made off the ui thread (a client pairing
    // over http mints a token from a server worker). only KEY_TOKENS lives here.
    fun observe(ctx: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(ctx).registerOnSharedPreferenceChangeListener(listener)

    fun unobserve(ctx: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(ctx).unregisterOnSharedPreferenceChangeListener(listener)

    fun isPaired(ctx: Context): Boolean = load(ctx).isNotEmpty()

    fun count(ctx: Context): Int = load(ctx).size

    fun pairingActive(nowMs: Long): Boolean {
        val p = pending ?: return false
        return nowMs <= p.expiresAt && p.attempts > 0
    }

    // open a pairing window and return its one-time code. no token is created yet;
    // redeeming the code mints one. replaces any code already pending.
    fun startPairing(nowMs: Long): String {
        val code = "%06d".format(rng.nextInt(1_000_000))
        pending = Pairing(code, nowMs + Defaults.PAIRING_WINDOW_MS, Defaults.CODE_ATTEMPTS)
        return code
    }

    // redeem a one-time code for a fresh per-client token. valid only inside an
    // open window; consumed on success. null on a closed window, or a bad,
    // expired, or attempt-exhausted code.
    fun redeem(ctx: Context, code: String, nowMs: Long, label: String?): Record? {
        val p = pending ?: return null
        if (nowMs > p.expiresAt || p.attempts <= 0) { pending = null; return null }
        if (!constantEquals(code, p.code)) { p.attempts -= 1; return null }
        pending = null
        return mint(ctx, Defaults.KIND_PAIRED, label)
    }

    // gui-only: mint a long-lived token to paste into a client that cannot pair.
    fun mintLegacy(ctx: Context, label: String?): Record = mint(ctx, Defaults.KIND_LEGACY, label)

    // gui-only: drop one token by id; that client is rejected, others keep working.
    fun revoke(ctx: Context, id: String): Boolean {
        val records = load(ctx).toMutableList()
        if (!records.removeAll { it.id == id }) return false
        save(ctx, records)
        return true
    }

    // forget every token and close any open window.
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TOKENS).apply()
        pending = null
    }

    // constant-time match against every stored token (no early exit reveals which
    // one matched). an empty or unknown candidate is rejected.
    fun verify(ctx: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        val cand = candidate.toByteArray(Charsets.UTF_8)
        var match = false
        for (r in load(ctx)) {
            if (MessageDigest.isEqual(r.token.toByteArray(Charsets.UTF_8), cand)) match = true
        }
        return match
    }

    private fun mint(ctx: Context, kind: String, label: String?): Record {
        val records = load(ctx).toMutableList()
        val token = Base64.encodeToString(ByteArray(Defaults.TOKEN_BYTES).also { rng.nextBytes(it) }, B64)
        var id: String
        do { id = hex(ByteArray(Defaults.TOKEN_ID_BYTES).also { rng.nextBytes(it) }) } while (records.any { it.id == id })
        val rec = Record(token, id, label?.trim()?.ifEmpty { null } ?: "client", kind, System.currentTimeMillis())
        records.add(rec)
        save(ctx, records)
        return rec
    }

    private fun load(ctx: Context): List<Record> {
        val raw = prefs(ctx).getString(KEY_TOKENS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Record(
                    o.getString("t"),
                    o.getString("id"),
                    o.optString("label", "client"),
                    o.optString("kind", Defaults.KIND_PAIRED),
                    o.optLong("created", 0),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, records: List<Record>) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject().put("t", r.token).put("id", r.id)
                    .put("label", r.label).put("kind", r.kind).put("created", r.created)
            )
        }
        prefs(ctx).edit().putString(KEY_TOKENS, arr.toString()).apply()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun constantEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
