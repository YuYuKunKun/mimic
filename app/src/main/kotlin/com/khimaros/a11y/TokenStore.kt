package com.khimaros.a11y

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

// the shared secret that gates every surface. it is stored in app-private
// shared preferences (unreadable by other apps without root) so the ui can
// reveal it for http/mcp client config. the same token works for all surfaces.
//
// two ways to obtain it: copy it from the app ui, or -- for the intents cli --
// exchange a short-lived 6-digit code for it (so a long token need not be typed).
object TokenStore {
    private const val PREFS = "auth"
    private const val KEY_TOKEN = "token"
    private const val B64 = Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING

    private val rng = SecureRandom()

    private data class Pending(val code: String, val expiresAt: Long, var attempts: Int)

    @Volatile
    private var pending: Pending? = null

    fun token(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)

    fun isPaired(ctx: Context): Boolean = token(ctx) != null

    // generate and store a fresh token, invalidating any previous one. returns it
    // so the ui can show it.
    fun rotate(ctx: Context): String {
        val token = Base64.encodeToString(ByteArray(24).also { rng.nextBytes(it) }, B64)
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    // forget the token entirely: every paired client is rejected until a new
    // token is minted (by showing the pairing code/token again).
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TOKEN).apply()
        pending = null
    }

    // begin a cli pairing: ensure a token exists (without rotating an existing
    // one, so http/mcp clients keep working), then bind a 6-digit code to it.
    fun beginPairing(ctx: Context, nowMs: Long): String {
        if (token(ctx) == null) rotate(ctx)
        val code = "%06d".format(rng.nextInt(1_000_000))
        pending = Pending(code, nowMs + Defaults.CODE_TTL_MS, Defaults.CODE_ATTEMPTS)
        return code
    }

    // redeem a code for the current token. null on a bad, expired, or exhausted
    // code.
    fun redeem(ctx: Context, code: String, nowMs: Long): String? {
        val p = pending ?: return null
        if (nowMs > p.expiresAt || p.attempts <= 0) { pending = null; return null }
        if (!constantEquals(code, p.code)) { p.attempts -= 1; return null }
        pending = null
        return token(ctx)
    }

    fun verify(ctx: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        val expected = token(ctx) ?: return false
        return constantEquals(expected, candidate)
    }

    private fun constantEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
