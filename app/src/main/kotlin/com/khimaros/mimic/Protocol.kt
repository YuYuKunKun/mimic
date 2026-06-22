package com.khimaros.mimic

// the intent protocol: action names and extra keys shared by the receiver and
// (by documentation) the cli. keeping them in one place is the single source of
// truth for SKILL.md and the e2e tests.

// command short names. these are the surface-agnostic identifiers used by the
// Commands core, the rest paths (/v1/<cmd>), and the mcp tool names. the intent
// surface prefixes them to form action strings.
object Cmd {
    const val DUMP = "DUMP"
    const val FIND = "FIND"
    const val TAP = "TAP"
    const val LONG_PRESS = "LONG_PRESS"
    const val SWIPE = "SWIPE"
    const val CLICK = "CLICK"
    const val SET_TEXT = "SET_TEXT"
    const val GLOBAL = "GLOBAL"
    const val LAUNCH = "LAUNCH"
    const val SCREENSHOT = "SCREENSHOT"
    const val STATUS = "STATUS"
    const val PACKAGES = "PACKAGES"
    const val PAIR = "PAIR"
}

object Actions {
    const val PREFIX = "com.khimaros.mimic.action."

    const val DUMP = PREFIX + Cmd.DUMP
    const val FIND = PREFIX + Cmd.FIND
    const val TAP = PREFIX + Cmd.TAP
    const val LONG_PRESS = PREFIX + Cmd.LONG_PRESS
    const val SWIPE = PREFIX + Cmd.SWIPE
    const val CLICK = PREFIX + Cmd.CLICK
    const val SET_TEXT = PREFIX + Cmd.SET_TEXT
    const val GLOBAL = PREFIX + Cmd.GLOBAL
    const val LAUNCH = PREFIX + Cmd.LAUNCH
    const val PAIR = PREFIX + Cmd.PAIR
    const val STATUS = PREFIX + Cmd.STATUS

    fun shortName(action: String?): String = (action ?: "").removePrefix(PREFIX)
}

// the localhost host surface (rest + mcp) binds loopback only.
object Host {
    const val ADDR = "127.0.0.1"
    const val PORT = 8473
    const val TOKEN_HEADER = "x-mimic-token"
    const val MCP_PROTOCOL = "2025-06-18"
    const val SERVER_NAME = "mimic"

    // single source of truth: app/build.gradle.kts defaultConfig.versionName.
    val VERSION: String = BuildConfig.VERSION_NAME
}

object Extras {
    const val TOKEN = "token"

    // view shaping
    const val FORMAT = "format"        // tree | flat | compact
    const val FILTER = "filter"        // interactive | text | visible | all
    const val MAX_DEPTH = "max_depth"
    const val PACKAGE = "package"
    const val FIELDS = "fields"        // comma-separated subset of NODE_FIELDS

    // query (FIND, and target of CLICK / SET_TEXT)
    const val BY = "by"                // text | id | class | desc
    const val QUERY = "query"
    const val MATCH = "match"          // exact | contains | regex

    // gestures
    const val X = "x"
    const val Y = "y"
    const val X2 = "x2"
    const val Y2 = "y2"
    const val DURATION = "duration"

    // misc
    const val TEXT = "text"            // SET_TEXT payload
    const val NAV = "nav"              // GLOBAL: back | home | recents | notifications
    const val CODE = "code"            // PAIR: 6-digit one-time pairing code
    const val LABEL = "label"          // PAIR: optional client label (e.g. hostname)
    const val ID = "id"                // a token's short handle (revoke/identify)
    const val KIND = "kind"            // a token's kind: paired | legacy

    // LAUNCH: at least one of these
    const val COMPONENT = "component"  // "pkg/.Activity"
    const val ACTION = "action"        // an intent action
    const val URI = "uri"              // data uri (with action, or ACTION_VIEW)

    // SCREENSHOT: format reuses FORMAT (png | jpeg)
    const val QUALITY = "quality"      // jpeg quality 1-100
    const val SCALE = "scale"          // downscale factor 0-1
}

object Defaults {
    const val FORMAT = "tree"
    const val FILTER = "all"
    const val MATCH = "contains"
    const val MAX_DEPTH = -1           // unlimited
    const val TAP_DURATION_MS = 50L
    const val LONG_PRESS_DURATION_MS = 600L
    const val SWIPE_DURATION_MS = 300L
    // kept under the ~10s broadcast-receiver dispatch window so a slow gesture
    // cannot hang the ordered broadcast and stall `am broadcast`.
    const val GESTURE_TIMEOUT_MS = 8_000L
    const val SCREENSHOT_TIMEOUT_MS = 5_000L
    const val SCREENSHOT_QUALITY = 90
    const val SCREENSHOT_FORMAT = "png"

    // pairing: an explicit "start pairing" opens a time-boxed window; a code is
    // valid only inside it, only once, and only for a bounded number of attempts.
    const val PAIRING_WINDOW_MS = 2 * 60_000L
    const val CODE_ATTEMPTS = 5
    const val TOKEN_BYTES = 24         // per-client secret
    const val TOKEN_ID_BYTES = 3       // short handle, 6 hex chars

    // a token's kind, recorded for display and provenance.
    const val KIND_PAIRED = "paired"   // minted by redeeming a one-time code
    const val KIND_LEGACY = "legacy"   // minted in the gui for manual config
}

// the full set of serializable node attributes; the caller may request a subset
// via the `fields` extra. "children" is only meaningful in tree format.
val NODE_FIELDS = listOf(
    "class", "text", "desc", "id", "bounds", "center", "actions", "children",
)
