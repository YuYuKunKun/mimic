package com.khimaros.mimic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// foreground service hosting the localhost surfaces. it binds a single token-
// gated http server on 127.0.0.1 and routes by path: /v1/* is the rest surface,
// /mcp is the in-app mcp endpoint. each route also checks its surface's toggle,
// so the two can be enabled independently while sharing one socket. zero deps:
// a tiny hand-rolled http/1.1 over java.net.
class HostService : Service() {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var boundAddress: String? = null
    private var acceptor: Thread? = null
    private lateinit var workers: ExecutorService

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        workers = Executors.newFixedThreadPool(4)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val desired = AppState.bindAddress(this)
        if (server == null || boundAddress != desired) startServer(desired)
        startForegroundNotice()
        return START_STICKY
    }

    override fun onDestroy() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        workers.shutdownNow()
        super.onDestroy()
    }

    // (re)bind on the chosen interface, falling back to loopback if that address
    // is unavailable (e.g. a saved lan ip that has since changed).
    private fun startServer(addr: String) {
        try { server?.close() } catch (_: Exception) {}
        val (socket, bound) = bind(addr) ?: bind(Net.LOOPBACK) ?: return
        boundAddress = bound
        server = socket
        acceptor = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                workers.execute { serve(client) }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun bind(addr: String): Pair<ServerSocket, String>? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(addr), Host.PORT))
        } to addr
    } catch (_: Exception) {
        null
    }

    private fun serve(client: Socket) {
        client.use {
            val input = client.getInputStream()
            val out = client.getOutputStream()
            val request = parseRequest(input) ?: return respond(out, "400 Bad Request", error("malformed request"))
            route(request, out)
        }
    }

    private fun route(req: Request, out: OutputStream) {
        // unauthenticated static routes: liveness, and the cli/skill so a client
        // can bootstrap or update itself before it has a token.
        if (req.method == "GET") when (req.path) {
            "/healthz" ->
                return respond(out, "200 OK", JSONObject().put("ok", true).put("server", Host.SERVER_NAME).toString())
            "/" -> return respond(out, "200 OK", indexText(), "text/plain; charset=utf-8")
            "/cli/mimic", "/mimic" -> return serveAsset(out, "mimic", "text/x-shellscript; charset=utf-8")
            "/SKILL.md", "/skill" -> return serveAsset(out, "SKILL.md", "text/markdown; charset=utf-8")
        }
        // pairing is unauthenticated by design: it is how a client without a token
        // obtains one, and it only succeeds while a gui-opened window is active.
        if (req.method == "POST" && req.path == "/pair") return pair(req, out)
        if (!TokenStore.verify(this, req.token())) {
            return respond(out, "401 Unauthorized", error("unauthorized: bad or missing token"))
        }
        when {
            req.path == "/mcp" && req.method == "POST" -> {
                if (!AppState.mcp(this)) return respond(out, "403 Forbidden", error("mcp surface disabled"))
                val (status, body) = Mcp.handle(this, req.body)
                respond(out, status, body)
            }
            req.path.startsWith("/v1/") -> {
                if (!AppState.http(this)) return respond(out, "403 Forbidden", error("http surface disabled"))
                rest(req, out)
            }
            else -> respond(out, "404 Not Found", error("no such route: ${req.path}"))
        }
    }

    // /v1/status (GET) or /v1/<cmd> (POST with a json body of arguments). binary
    // payloads (screenshot) are returned as raw image bytes; everything else as
    // the json envelope.
    private fun rest(req: Request, out: OutputStream) {
        val cmd = req.path.removePrefix("/v1/").uppercase()
        val args = if (req.body.isNotBlank()) JSONObject(req.body) else JSONObject()
        val get = { k: String -> req.query[k] ?: if (args.has(k) && !args.isNull(k)) args.get(k).toString() else null }
        val result = Commands.run(this, cmd, get)
        val data = result.data
        if (result.ok && data is ByteArray) {
            respondBytes(out, "200 OK", data, imageMime(get(Extras.FORMAT)))
        } else {
            respond(out, "200 OK", result.toJson().toString())
        }
    }

    private fun imageMime(format: String?): String =
        if (format == "jpeg" || format == "jpg") "image/jpeg" else "image/png"

    // redeem a one-time code (json body or query) for a fresh per-client token.
    private fun pair(req: Request, out: OutputStream) {
        val args = try {
            if (req.body.isNotBlank()) JSONObject(req.body) else JSONObject()
        } catch (_: Exception) { JSONObject() }
        val code = args.optString(Extras.CODE).ifEmpty { req.query[Extras.CODE] ?: "" }
        val label = args.optString(Extras.LABEL).ifEmpty { req.query[Extras.LABEL] ?: "" }
        val rec = TokenStore.redeem(this, code, System.currentTimeMillis(), label)
            ?: return respond(out, "401 Unauthorized", error("invalid or expired pairing code"))
        val data = JSONObject().put("token", rec.token).put("id", rec.id).put("label", rec.label)
        respond(out, "200 OK", Commands.Result(true, data, null).toJson().toString())
    }

    // ---- minimal http ----

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun token(): String? = headers[Host.TOKEN_HEADER] ?: query["token"]
    }

    private fun parseRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) readBody(input, length) else ""
        return Request(parts[0].uppercase(), path, query, headers, body)
    }

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&')
        .filter { it.contains('=') }
        .associate {
            val k = Uri.decode(it.substringBefore('='))
            val v = Uri.decode(it.substringAfter('='))
            k to v
        }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun respond(out: OutputStream, status: String, body: String, contentType: String = "application/json") =
        respondBytes(out, status, body.toByteArray(Charsets.UTF_8), contentType)

    private fun respondBytes(out: OutputStream, status: String, bytes: ByteArray, contentType: String) {
        val head = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // serve a bundled asset (the cli script or skill doc) verbatim.
    private fun serveAsset(out: OutputStream, name: String, contentType: String) {
        val bytes = try {
            assets.open(name).use { it.readBytes() }
        } catch (_: Exception) {
            return respond(out, "404 Not Found", error("missing asset: $name"))
        }
        respondBytes(out, "200 OK", bytes, contentType)
    }

    private fun indexText(): String {
        val host = Net.displayHost(boundAddress ?: Net.LOOPBACK)
        return """
        mimic host server.

        bootstrap the cli:
          curl -s http://$host:${Host.PORT}/cli/mimic -o mimic && chmod +x mimic
        docs:
          http://$host:${Host.PORT}/SKILL.md
        mcp endpoint:
          http://$host:${Host.PORT}/mcp  (header x-mimic-token)
        """.trimIndent() + "\n"
    }

    private fun error(message: String): String =
        Commands.Result(false, null, message).toJson().toString()

    // ---- foreground notification ----

    private fun startForegroundNotice() {
        val channelId = "host"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(channelId, "host server", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val surfaces = buildList {
            if (AppState.http(this@HostService)) add("http")
            if (AppState.mcp(this@HostService)) add("mcp")
        }.joinToString("+").ifEmpty { "idle" }
        val host = Net.displayHost(boundAddress ?: AppState.bindAddress(this))
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("mimic")
            .setContentText("serving $surfaces on $host:${Host.PORT}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
