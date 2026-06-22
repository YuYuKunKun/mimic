package com.khimaros.mimic

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

// a dark, minimal onboarding screen split into bottom tabs: general (accessibility
// + draw-over status, require-approval, boot), clients (pairing + per-client list
// with each client's grants), and surfaces (intents/http/mcp + bind). plain
// framework widgets, no androidx; no state of its own beyond AppState/TokenStore/
// Permissions.
class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var creds: TextView
    private lateinit var clients: LinearLayout
    private lateinit var address: TextView
    private lateinit var lanWarning: TextView
    private lateinit var overlayState: TextView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<Button>

    // the bind options and the currently selected interface.
    private var bindOptions: List<String> = emptyList()
    private var currentBind: String = Net.LOOPBACK

    // a client pairing over http mints a token from a server worker thread, off
    // the ui; refresh the list live so it does not require an exit/reopen.
    private val authListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    // ticks down the visible pairing code, clearing it once used or expired.
    private val ticker = Handler(Looper.getMainLooper())
    private var countdown: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serviceStatus = findViewById(R.id.service_status)
        creds = findViewById(R.id.creds)
        clients = findViewById(R.id.clients)
        overlayState = findViewById(R.id.overlay_state)
        setupTabs()

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // the code and token are copied to the clipboard the moment they are
        // shown, so the common path is one tap.
        findViewById<Button>(R.id.start_pairing).setOnClickListener {
            val code = TokenStore.startPairing(System.currentTimeMillis())
            copy(code)
            startCountdown(code)
        }
        findViewById<Button>(R.id.legacy_token).setOnClickListener {
            stopCountdown()
            val rec = TokenStore.mintLegacy(this, Defaults.KIND_LEGACY)
            creds.text = getString(R.string.legacy_fmt, rec.token)
            refresh()
            copy(rec.token)
        }
        findViewById<Button>(R.id.clear_paired).setOnClickListener {
            stopCountdown()
            TokenStore.clear(this)
            Permissions.clearAll(this)
            creds.text = getString(R.string.cleared)
            refresh()
        }

        bindSurface(R.id.sw_intents, getString(R.string.sw_intents), AppState.intents(this)) {
            AppState.setIntents(this, it)
        }
        // the http/mcp labels carry the full url and are filled by updateAddress
        // (they depend on the chosen bind interface).
        bindSurface(R.id.sw_http, "", AppState.http(this)) {
            if (it) ensureNotificationPermission()
            AppState.setHttp(this, it)
        }
        bindSurface(R.id.sw_mcp, "", AppState.mcp(this)) {
            if (it) ensureNotificationPermission()
            AppState.setMcp(this, it)
        }
        findViewById<Switch>(R.id.sw_auth).apply {
            isChecked = AppState.requireAuth(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppState.setRequireAuth(this@MainActivity, checked) }
        }
        findViewById<Switch>(R.id.sw_approval).apply {
            isChecked = AppState.requireApproval(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppState.setRequireApproval(this@MainActivity, checked) }
        }
        findViewById<Button>(R.id.grant_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Switch>(R.id.sw_boot).apply {
            isChecked = AppState.startOnBoot(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppState.setStartOnBoot(this@MainActivity, checked) }
        }
        setupBind()
    }

    // bottom tabs: show one page at a time, dim the inactive tab labels.
    private fun setupTabs() {
        pages = listOf(R.id.page_general, R.id.page_surfaces, R.id.page_clients)
            .map { findViewById<View>(it) }
        tabs = listOf(R.id.tab_general, R.id.tab_surfaces, R.id.tab_clients)
            .map { findViewById<Button>(it) }
        tabs.forEachIndexed { i, b -> b.setOnClickListener { showTab(i) } }
        showTab(0)
    }

    private fun showTab(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, b -> b.alpha = if (i == index) 1f else 0.5f }
    }

    // populate the bind-interface spinner (loopback, each detected lan ip, then
    // 0.0.0.0) and reflect the selection in the copyable address line.
    private fun setupBind() {
        address = findViewById(R.id.address)
        lanWarning = findViewById(R.id.lan_warning)
        currentBind = AppState.bindAddress(this)

        val opts = LinkedHashMap<String, String>()
        opts[Net.LOOPBACK] = "${Net.LOOPBACK} (this device only)"
        for ((ip, name) in Net.lanAddresses()) opts[ip] = "$ip ($name)"
        opts[Net.ALL] = "${Net.ALL} (all interfaces)"
        opts.putIfAbsent(currentBind, currentBind)  // a saved ip that is gone now
        bindOptions = opts.keys.toList()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opts.values.toList())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.bind_spinner).apply {
            this.adapter = adapter
            setSelection(bindOptions.indexOf(currentBind).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val chosen = bindOptions[pos]
                    if (chosen != currentBind) {
                        currentBind = chosen
                        AppState.setBindAddress(this@MainActivity, chosen)
                        updateAddress()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        findViewById<Button>(R.id.copy_address).setOnClickListener { copy(addressValue()) }
        updateAddress()
    }

    private fun addressValue(): String = "http://${Net.displayHost(currentBind)}:${Host.PORT}"

    private fun updateAddress() {
        address.text = getString(R.string.address_fmt, Net.displayHost(currentBind), Host.PORT)
        lanWarning.visibility = if (Net.isLoopback(currentBind)) View.GONE else View.VISIBLE
        val base = addressValue()
        findViewById<Switch>(R.id.sw_http).text = getString(R.string.sw_http, base)
        findViewById<Switch>(R.id.sw_mcp).text = getString(R.string.sw_mcp, base)
    }

    private fun copy(value: String?) {
        if (value.isNullOrEmpty()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("mimic", value))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // the host server process may have been killed (reinstall, swipe-away)
        // while its surface prefs stay on; reconcile so opening the app brings
        // the server back up to match the toggles.
        AppState.applyHost(this)
        showState(serviceStatus, R.string.service_state, MimicService.isEnabled())
        showState(overlayState, R.string.overlay_state, Settings.canDrawOverlays(this))
        refresh()
        updateAddress()
        TokenStore.observe(this, authListener)
    }

    override fun onPause() {
        TokenStore.unobserve(this, authListener)
        stopCountdown()
        super.onPause()
    }

    // show the pairing code and tick its remaining time each second; clear it the
    // moment the window closes (a client redeemed it) or the timeout is reached.
    private fun startCountdown(code: String) {
        stopCountdown()
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (!TokenStore.pairingActive(now)) {
                    creds.text = ""
                    countdown = null
                    return
                }
                val secs = (TokenStore.pairingRemainingMs(now) + 999) / 1000
                creds.text = getString(R.string.code_fmt, secs, code)
                ticker.postDelayed(this, 1000)
            }
        }
        countdown = r
        ticker.post(r)
    }

    private fun stopCountdown() {
        countdown?.let { ticker.removeCallbacks(it) }
        countdown = null
    }

    // rebuild the clients list (clients tab): one block per token.
    private fun refresh() {
        clients.removeAllViews()
        val records = TokenStore.list(this)
        if (records.isEmpty()) {
            clients.addView(mutedText(getString(R.string.no_clients)))
            return
        }
        for (r in records) clients.addView(clientRow(r))
    }

    // one client: a header row (label/id/kind, mode toggle, revoke) followed by its
    // authorization grants, each individually revocable.
    private fun clientRow(r: TokenStore.Record): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.client_row_fmt, r.label, r.id, r.kind)
            setTextColor(getColor(R.color.fg))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = getString(R.string.mode_fmt, r.mode)
            contentDescription = getString(R.string.mode_desc, r.id)
            setOnClickListener {
                val next = if (r.mode == Defaults.MODE_ALLOW_ALL) Defaults.MODE_ASK else Defaults.MODE_ALLOW_ALL
                TokenStore.setMode(this@MainActivity, r.id, next)
                refresh()
            }
        })
        header.addView(Button(this).apply {
            text = getString(R.string.revoke)
            // a stable, non-uppercased handle the e2e (and a human) can target.
            contentDescription = getString(R.string.revoke_desc, r.id)
            setOnClickListener {
                TokenStore.revoke(this@MainActivity, r.id)
                Permissions.clearForToken(this@MainActivity, r.id)
                refresh()
            }
        })
        block.addView(header)
        for (g in Permissions.rules(this, r.id)) block.addView(grantRow(r.id, g))
        return block
    }

    private fun grantRow(tokenId: String, g: Permissions.Rule): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 0, 0, 0)
        }
        val target = if (g.target == Permissions.TARGET_ANY) "any app" else g.target
        row.addView(TextView(this).apply {
            text = getString(R.string.grant_row_fmt, if (g.allow) "allow" else "deny", g.cls, target)
            setTextColor(getColor(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = getString(R.string.revoke)
            contentDescription = getString(R.string.revoke_desc, g.id)
            setOnClickListener {
                Permissions.revoke(this@MainActivity, tokenId, g.id)
                refresh()
            }
        })
        return row
    }

    // a "<thing>: enabled/disabled" line, green when enabled, muted otherwise.
    private fun showState(view: TextView, fmt: Int, enabled: Boolean) {
        view.text = getString(fmt, getString(if (enabled) R.string.enabled else R.string.disabled))
        view.setTextColor(getColor(if (enabled) R.color.accent else R.color.muted))
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
