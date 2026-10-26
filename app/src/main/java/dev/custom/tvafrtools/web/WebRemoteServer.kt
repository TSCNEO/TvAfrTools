package dev.custom.tvafrtools.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import dev.custom.tvafrtools.config.QuickAction
import dev.custom.tvafrtools.server.ServerClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Embedded HTTP Web Remote & Mobile Keyboard / Clipboard Injector (`http://<TV_IP>:8099`).
 * Works from any smartphone or PC on the local Wi-Fi/LAN without installing any phone app.
 */
class WebRemoteServer(
    private val context: Context,
    private val onExecuteAction: (QuickAction) -> Unit,
    private val onBanner: (String) -> Unit
) {
    companion object {
        const val PORT = 8099

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return "127.0.0.1"
        }
    }

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                val server = ServerSocket(PORT)
                serverSocket = server
                while (running) {
                    val client = server.accept()
                    executor.execute { handleHttpClient(client) }
                }
            } catch (_: Exception) {
            } finally {
                running = false
            }
        }, "TvAfrWebRemote").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = PrintWriter(s.getOutputStream(), true)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val pathAndQuery = parts[1]

            if (pathAndQuery.startsWith("/api/action")) {
                val q = parseQuery(pathAndQuery)
                val id = q["id"].orEmpty()
                val action = QuickAction.fromId(id)
                if (action != QuickAction.NONE) {
                    mainHandler.post { onExecuteAction(action) }
                }
                sendJsonResponse(out, """{"ok":true,"action":"$id"}""")
                return
            }

            if (pathAndQuery.startsWith("/api/key")) {
                val q = parseQuery(pathAndQuery)
                val code = q["code"]?.toIntOrNull() ?: 0
                if (code > 0) {
                    ServerClient.injectKey(code)
                }
                sendJsonResponse(out, """{"ok":true,"key":$code}""")
                return
            }

            if (pathAndQuery.startsWith("/api/text")) {
                val q = parseQuery(pathAndQuery)
                val text = q["v"].orEmpty()
                if (text.isNotEmpty()) {
                    mainHandler.post {
                        try {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("TvAfrRemote", text))
                            onBanner("📋 Texto pegado desde el móvil: \"$text\"")
                        } catch (_: Exception) {
                        }
                    }
                    Thread {
                        Thread.sleep(120L)
                        ServerClient.injectKey(KeyEvent.KEYCODE_PASTE)
                    }.start()
                }
                sendJsonResponse(out, """{"ok":true}""")
                return
            }

            sendHtmlResponse(out, buildWebRemoteHtml())
        }
    }

    private fun parseQuery(pathAndQuery: String): Map<String, String> {
        val idx = pathAndQuery.indexOf('?')
        if (idx < 0) return emptyMap()
        val query = pathAndQuery.substring(idx + 1)
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                map[URLDecoder.decode(kv[0], "UTF-8")] = URLDecoder.decode(kv[1], "UTF-8")
            }
        }
        return map
    }

    private fun sendJsonResponse(out: PrintWriter, json: String) {
        out.print("HTTP/1.1 200 OK\r\n")
        out.print("Content-Type: application/json; charset=utf-8\r\n")
        out.print("Content-Length: ${json.toByteArray(Charsets.UTF_8).size}\r\n")
        out.print("Connection: close\r\n\r\n")
        out.print(json)
        out.flush()
    }

    private fun sendHtmlResponse(out: PrintWriter, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        out.print("HTTP/1.1 200 OK\r\n")
        out.print("Content-Type: text/html; charset=utf-8\r\n")
        out.print("Content-Length: ${bytes.size}\r\n")
        out.print("Connection: close\r\n\r\n")
        out.print(html)
        out.flush()
    }

    private fun buildWebRemoteHtml(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
<title>TvAfrTools Remote</title>
<style>
  body { background:#0b1120; color:#f8fafc; font-family:system-ui,-apple-system,sans-serif; margin:0; padding:16px; text-align:center; }
  h1 { font-size:20px; margin:4px 0 12px; color:#38bdf8; }
  .grid { display:grid; grid-template-columns:repeat(2,1fr); gap:10px; max-width:440px; margin:0 auto 14px; }
  .dpad { display:grid; grid-template-columns:repeat(3,1fr); gap:10px; max-width:300px; margin:0 auto 16px; }
  button { background:#1e293b; color:#fff; border:1px solid #334155; border-radius:12px; padding:14px 10px; font-size:14px; font-weight:600; cursor:pointer; }
  button:active { background:#0284c7; }
  .accent { background:#0369a1; border-color:#38bdf8; }
  .box { max-width:440px; margin:0 auto 14px; display:flex; gap:8px; }
  input { flex:1; background:#0f172a; color:#fff; border:1px solid #334155; border-radius:10px; padding:12px; font-size:15px; }
</style>
</head>
<body>
  <h1>⚡ TvAfrTools Mando Web</h1>
  <div class="grid">
    <button class="accent" onclick="act('one_click_afr')">🎬 1-Click AFR</button>
    <button class="accent" onclick="act('afr_panel')">📺 Selector Hz</button>
    <button onclick="act('hud_toggle')">📊 HUD Técnico</button>
    <button onclick="act('recent_apps_switcher')">🗂 Multitarea / RAM</button>
    <button onclick="act('real_screen_toggle')">🌑 Pantalla Off/On</button>
    <button onclick="act('clean_background_ram')">🧹 Limpiar RAM</button>
    <button onclick="act('hdr_mode_cycle')">🌈 Modo HDR/SDR</button>
    <button onclick="act('audio_passthrough_cycle')">🔊 Audio Passthrough</button>
  </div>
  <div class="box">
    <input id="txt" placeholder="Pegar texto / contraseña en la TV..."/>
    <button class="accent" onclick="sendTxt()">Enviar</button>
  </div>
  <div class="dpad">
    <div></div><button onclick="key(19)">▲</button><div></div>
    <button onclick="key(21)">◀</button><button class="accent" onclick="key(23)">OK</button><button onclick="key(22)">▶</button>
    <button onclick="key(4)">Atrás</button><button onclick="key(20)">▼</button><button onclick="key(3)">Home</button>
  </div>
  <script>
    function act(id){ fetch('/api/action?id='+encodeURIComponent(id)); }
    function key(c){ fetch('/api/key?code='+c); }
    function sendTxt(){
      const v = document.getElementById('txt').value;
      if(!v) return;
      fetch('/api/text?v='+encodeURIComponent(v));
      document.getElementById('txt').value='';
    }
  </script>
</body>
</html>
""".trimIndent()
}
