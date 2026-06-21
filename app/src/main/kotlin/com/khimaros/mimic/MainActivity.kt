package com.khimaros.mimic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

// a single onboarding screen, dark and minimal: enable the service, reveal the
// credentials, then independently toggle the three command surfaces. plain
// framework widgets, no app state of its own beyond what AppState/TokenStore hold.
class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var creds: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serviceStatus = findViewById(R.id.service_status)
        creds = findViewById(R.id.creds)

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.show_creds).setOnClickListener {
            val code = TokenStore.beginPairing(this, System.currentTimeMillis())
            creds.text = getString(R.string.creds_fmt, code, TokenStore.token(this))
        }
        findViewById<Button>(R.id.clear_paired).setOnClickListener {
            TokenStore.clear(this)
            creds.text = getString(R.string.cleared)
        }

        bindSurface(R.id.sw_intents, getString(R.string.sw_intents), AppState.intents(this)) {
            AppState.setIntents(this, it)
        }
        bindSurface(R.id.sw_http, getString(R.string.sw_http, Host.PORT), AppState.http(this)) {
            if (it) ensureNotificationPermission()
            AppState.setHttp(this, it)
        }
        bindSurface(R.id.sw_mcp, getString(R.string.sw_mcp, Host.PORT), AppState.mcp(this)) {
            if (it) ensureNotificationPermission()
            AppState.setMcp(this, it)
        }
        findViewById<Switch>(R.id.sw_boot).apply {
            isChecked = AppState.startOnBoot(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppState.setStartOnBoot(this@MainActivity, checked) }
        }
    }

    override fun onResume() {
        super.onResume()
        // the host server process may have been killed (reinstall, swipe-away)
        // while its surface prefs stay on; reconcile so opening the app brings
        // the server back up to match the toggles.
        AppState.applyHost(this)
        val state = getString(if (MimicService.isEnabled()) R.string.enabled else R.string.disabled)
        serviceStatus.text = getString(R.string.service_state, state)
    }

    private fun bindSurface(id: Int, label: String, initial: Boolean, set: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            text = label
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
