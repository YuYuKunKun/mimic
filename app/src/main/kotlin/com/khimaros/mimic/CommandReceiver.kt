package com.khimaros.mimic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors

// the intents surface. parses an ordered broadcast, authenticates it, runs the
// command core, and writes a base64-json envelope into the broadcast result data
// so `am`/`termux-am broadcast` can return it. work runs off the main thread
// (goAsync) so blocking gesture dispatch does not deadlock the gesture callback.
// when the intents surface is stopped this receiver is disabled at the component
// level and is never invoked at all.
class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val ctx = context.applicationContext
        EXECUTOR.execute {
            val (code, payload) = try {
                handle(ctx, intent)
            } catch (t: Throwable) {
                GENERIC_ERROR to envelope(Commands.Result(false, null, t.message ?: t.toString()))
            }
            pending.setResultCode(code)
            pending.setResultData(payload)
            pending.finish()
        }
    }

    private fun handle(ctx: Context, intent: Intent): Pair<Int, String> {
        val get = { k: String ->
            val v = intent.getStringExtra(k)
            // keep a wait under the broadcast window so the receiver does not anr;
            // longer waits need the http surface.
            if (k == Extras.TIMEOUT && v != null)
                minOf(v.toDoubleOrNull() ?: Defaults.WAIT_INTENTS_MAX_S, Defaults.WAIT_INTENTS_MAX_S).toString()
            else v
        }
        val action = Actions.shortName(intent.action)

        // pairing is intent-specific and unauthenticated (it is how a client
        // obtains the token); status is unauthenticated too.
        if (action == Cmd.PAIR) {
            val rec = TokenStore.redeem(ctx, get(Extras.CODE) ?: "", System.currentTimeMillis(), get(Extras.LABEL))
                ?: return AUTH_FAILED to envelope(Commands.Result(false, null, "invalid or expired pairing code"))
            val data = JSONObject().put("token", rec.token).put("id", rec.id).put("label", rec.label)
            return OK to envelope(Commands.Result(true, data, null))
        }
        if (action == Cmd.STATUS) return OK to envelope(Commands.run(ctx, action, get))

        // auth on: a valid token is required; auth off: run anonymously.
        val record = if (AppState.requireAuth(ctx)) {
            TokenStore.find(ctx, get(Extras.TOKEN))
                ?: return AUTH_FAILED to envelope(Commands.Result(false, null, "unauthorized: bad or missing token"))
        } else null

        val result = Commands.runGuarded(ctx, action, get, record, Defaults.PROMPT_TIMEOUT_INTENTS_MS)
        return (if (result.ok) OK else GENERIC_ERROR) to envelope(result)
    }

    private fun envelope(r: Commands.Result): String =
        Base64.encodeToString(r.toJson().toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    companion object {
        private const val OK = 0
        private const val GENERIC_ERROR = 1
        private const val AUTH_FAILED = 2

        // commands serialize through one worker so a blocking gesture cannot
        // overlap the next command.
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
