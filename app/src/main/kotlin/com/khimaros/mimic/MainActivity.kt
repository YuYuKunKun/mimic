package com.khimaros.mimic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

// a single onboarding screen, dark and minimal: enable the service, pair a client
// (or reveal a legacy token), revoke clients, then independently toggle the three
// command surfaces. plain framework widgets, no app state of its own beyond what
// AppState/TokenStore hold. token minting and revocation are gui-only by design.
class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var creds: TextView
    private lateinit var clients: LinearLayout

    // a client pairing over http mints a token from a server worker thread, off
    // the ui; refresh the list live so it does not require an exit/reopen.
    private val authListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshClients() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serviceStatus = findViewById(R.id.service_status)
        creds = findViewById(R.id.creds)
        clients = findViewById(R.id.clients)

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.start_pairing).setOnClickListener {
            val code = TokenStore.startPairing(System.currentTimeMillis())
            creds.text = getString(R.string.code_fmt, Defaults.PAIRING_WINDOW_MS / 60_000L, code)
        }
        findViewById<Button>(R.id.legacy_token).setOnClickListener {
            val rec = TokenStore.mintLegacy(this, Defaults.KIND_LEGACY)
            creds.text = getString(R.string.legacy_fmt, rec.token)
            refreshClients()
        }
        findViewById<Button>(R.id.clear_paired).setOnClickListener {
            TokenStore.clear(this)
            creds.text = getString(R.string.cleared)
            refreshClients()
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
        refreshClients()
        TokenStore.observe(this, authListener)
    }

    override fun onPause() {
        TokenStore.unobserve(this, authListener)
        super.onPause()
    }

    // rebuild the paired-clients list: one row per token (label, id, kind) with a
    // revoke button keyed by the token id.
    private fun refreshClients() {
        clients.removeAllViews()
        val records = TokenStore.list(this)
        if (records.isEmpty()) {
            clients.addView(mutedText(getString(R.string.no_clients)))
            return
        }
        for (r in records) clients.addView(clientRow(r))
    }

    private fun clientRow(r: TokenStore.Record): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = getString(R.string.client_row_fmt, r.label, r.id, r.kind)
            setTextColor(getColor(R.color.fg))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val revoke = Button(this).apply {
            text = getString(R.string.revoke)
            // a stable, non-uppercased handle the e2e (and a human) can target.
            contentDescription = getString(R.string.revoke_desc, r.id)
            setOnClickListener {
                TokenStore.revoke(this@MainActivity, r.id)
                refreshClients()
            }
        }
        row.addView(label)
        row.addView(revoke)
        return row
    }

    private fun mutedText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.muted))
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
