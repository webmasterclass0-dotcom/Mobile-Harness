package com.jarves.mh.control

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tiny HTTP server bound to 127.0.0.1 that lets the AI agent running inside the
 * PRoot Linux environment drive the Android side: taps, swipes, text entry,
 * app launch, UI tree reads, screenshots and the app's own shell.
 *
 * The Linux runtime and the app share the device's loopback interface, so the
 * agent simply calls `curl http://127.0.0.1:8765/...`. See scripts/phone for a
 * ready-made CLI wrapper.
 *
 * Security model:
 * - Bound to loopback only; never exposed to Wi-Fi.
 * - Master switch [PhoneControlSettings.enabled] — server does not bind at all
 *   while the feature is disabled.
 * - Optional X-Access-Token (on by default) because every app on the device can
 *   reach loopback.
 */
object PhoneControlServer {

    private const val TAG = "PhoneControl"
    const val DEFAULT_PORT = 8765
    private const val BODY_LIMIT = 256 * 1024

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptor: Thread? = null
    @Volatile private var appContext: Context? = null

    private val workers: ExecutorService = Executors.newCachedThreadPool()

    fun isRunning(): Boolean = serverSocket != null

    /** (Re)applies the enabled preference: starts or stops the server. */
    fun refresh(context: Context) {
        val settings = PhoneControlSettings(context)
        if (settings.enabled) start(context) else stop()
    }

    @Synchronized
    fun start(context: Context) {
        if (serverSocket != null) return
        val settings = PhoneControlSettings(context)
        if (!settings.enabled) return

        val token = settings.issueTokenIfNeeded()
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), DEFAULT_PORT))
            }
        } catch (e: IOException) {
            // Port taken (unlikely): fall back to an ephemeral port.
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                }
            } catch (fallback: IOException) {
                Log.e(TAG, "failed to bind control server", fallback)
                return
            }
        }

        appContext = context.applicationContext
        serverSocket = socket
        val port = socket.localPort
        PhoneControlRuntime.port = port
        PhoneControlRuntime.token = token
        Log.i(TAG, "control server listening on 127.0.0.1:$port (token required=${
            settings.requireToken
        })")

        acceptor = Thread({
            while (serverSocket === socket) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                workers.execute { handleConnection(client) }
            }
        }, "phone-control-acceptor").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        acceptor = null
        PhoneControlRuntime.port = 0
        PhoneControlRuntime.token = ""
        appContext = null
    }

    // ---- connection handling -------------------------------------------

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            val request = runCatching { readRequest(client.getInputStream()) }.getOrNull()
                ?: return
            val context = appContext
            if (context == null) {
                respond(client, 503, errJson("control server is stopping").toString())
                return
            }

            val settings = PhoneControlSettings(context)
            val authorized = !settings.requireToken ||
                request.headers["x-access-token"] == settings.issueTokenIfNeeded()

            val response: JSONObject
            val status: Int
            if (!authorized && request.path != "/health") {
                status = 403
                response = errJson("missing or invalid X-Access-Token")
            } else {
                val routed = route(context, request)
                status = if (routed.optBoolean("ok")) 200 else 400
                response = routed
            }
            respond(client, status, response.toString())
        }
    }

    private fun route(context: Context, request: Request): JSONObject {
        return try {
            when {
                request.method == "GET" && request.path == "/health" ->
                    PhoneControlActions.health(context, PhoneControlRuntime.port)

                request.method == "GET" && request.path == "/ui" ->
                    PhoneControlActions.uiTree()

                request.method == "GET" && request.path == "/apps" ->
                    PhoneControlActions.apps(context)

                request.method == "GET" && request.path == "/screenshot" ->
                    PhoneControlActions.screenshot()

                request.method == "POST" && request.path == "/tap" -> {
                    val body = request.json()
                    PhoneControlActions.tap(
                        text = body.optString("text").takeIf { it.isNotBlank() },
                        resourceId = body.optString("resourceId").takeIf { it.isNotBlank() },
                        x = optIntOrNull(body, "x"),
                        y = optIntOrNull(body, "y"),
                    )
                }

                request.method == "POST" && request.path == "/longpress" -> {
                    val body = request.json()
                    PhoneControlActions.longPress(
                        x = body.optInt("x"),
                        y = body.optInt("y"),
                        durationMs = body.optLong("durationMs", 600L),
                    )
                }

                request.method == "POST" && request.path == "/swipe" -> {
                    val body = request.json()
                    PhoneControlActions.swipe(
                        x1 = body.optDouble("x1").toFloat(),
                        y1 = body.optDouble("y1").toFloat(),
                        x2 = body.optDouble("x2").toFloat(),
                        y2 = body.optDouble("y2").toFloat(),
                        durationMs = body.optLong("durationMs", 300L),
                    )
                }

                request.method == "POST" && request.path == "/type" -> {
                    val body = request.json()
                    PhoneControlActions.type(body.optString("text"))
                }

                request.method == "POST" && request.path == "/key" -> {
                    val body = request.json()
                    PhoneControlActions.key(body.optString("key"))
                }

                request.method == "POST" && request.path == "/open" -> {
                    val body = request.json()
                    PhoneControlActions.openApp(context, body.optString("package"))
                }

                request.method == "POST" && request.path == "/shell" -> {
                    val body = request.json()
                    val array = body.optJSONArray("args")
                    val args = if (array != null) {
                        (0 until array.length()).map { array.optString(it) }
                    } else {
                        body.optString("command").trim().split("\\s+".toRegex())
                            .filter { it.isNotEmpty() }
                    }
                    PhoneControlActions.shell(args, body.optLong("timeoutMs", 30_000L))
                }

                else -> errJson("unknown endpoint: ${request.method} ${request.path}")
            }
        } catch (e: Exception) {
            errJson("request failed: ${e.message}")
        }
    }

    private fun optIntOrNull(json: JSONObject, key: String): Int? =
        if (json.has(key)) json.optInt(key) else null

    // ---- raw HTTP -------------------------------------------------------

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        private val body: ByteArray,
    ) {
        fun json(): JSONObject {
            if (body.isEmpty()) return JSONObject()
            return JSONObject(String(body, Charsets.UTF_8))
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val first = readLine(input)?.trim() ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, BODY_LIMIT) ?: 0
        val body = if (contentLength > 0) {
            // readNBytes is API 33+; manual loop keeps Android 9–12 working.
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buffer, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        } else {
            ByteArray(0)
        }
        return Request(method, target.split("?")[0], headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (prev == '\r'.code && b == '\n'.code) {
                // drop the trailing \r as well
                val bytes = out.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
            }
            prev = b
            out.write(b)
        }
    }

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        try {
            val out: OutputStream = client.getOutputStream()
            out.write("HTTP/1.1 $status ${reason(status)}\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.UTF_8))
            out.write("Connection: close\r\n".toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: IOException) {
            // client disconnected; nothing to do
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private fun errJson(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)
}