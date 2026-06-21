package com.khimaros.mimic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// restores the surfaces after a reboot when the user opted into launch on boot.
// the intents component's enabled state persists on its own; this restarts the
// foreground HostService if the http/mcp surfaces were on. it performs no
// accessibility action itself, so it stays enabled even when surfaces are off.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && AppState.startOnBoot(context)) {
            AppState.applyHost(context)
        }
    }
}
