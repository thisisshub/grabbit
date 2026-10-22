@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.usenetstream.link

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.usenetstream.MainViewModel
import dev.usenetstream.engine.link.LinkHandler
import dev.usenetstream.engine.link.LinkProtocol
import dev.usenetstream.engine.link.LinkServer
import dev.usenetstream.engine.link.PairingGuard
import dev.usenetstream.engine.newznab.IndexerConfig
import dev.usenetstream.engine.nntp.NntpConfig
import dev.usenetstream.ui.DarkPalette
import dev.usenetstream.ui.tv.TvButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * TV side of the phone link: runs [LinkServer] while the TV app is open, announces the TV on the home
 * network (NSD), keeps paired phones in prefs, and forwards their requests to the view model.
 */
class TvLink(private val context: Context, private val vm: MainViewModel) {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var server: LinkServer? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    val guard = PairingGuard(initialTokens = prefs.all.filterKeys { it.startsWith("t:") }.mapKeys { it.key.removePrefix("t:") }.mapValues { it.value.toString() })

    private val _paired = MutableStateFlow(guard.paired)
    val paired: StateFlow<Map<String, String>> = _paired
    private val _code = MutableStateFlow(guard.code)
    val code: StateFlow<String> = _code

    val tvName: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} TV"

    /** Phone currently asking to pair; the TV shows the code on top of whatever is on screen. */
    private val _pairRequest = MutableStateFlow<String?>(null)
    val pairRequest: StateFlow<String?> = _pairRequest

    fun dismissPairRequest() {
        _pairRequest.value = null
    }

    fun start() {
        if (server != null) return
        server = runCatching {
            LinkServer(guard, handler, ::setupPage).start()
        }.onFailure { vm.notifyUser("Phone link unavailable: ${it.message}") }.getOrNull()
        server ?: return
        register()
    }

    fun stop() {
        server?.close()
        server = null
        nsdListener?.let { runCatching { nsd().unregisterService(it) } }
        nsdListener = null
    }

    fun unpair(token: String) {
        guard.unpair(token)
        prefs.edit().remove("t:$token").apply()
        _paired.value = guard.paired
    }

    fun refreshCode() {
        _code.value = guard.code
    }

    /** This TV's address on the home network, for the QR code. */
    fun address(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private fun nsd() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = tvName
            serviceType = LinkProtocol.SERVICE_TYPE
            port = LinkProtocol.PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = Unit
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
        }
        nsdListener = l
        runCatching { nsd().registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private val handler = object : LinkHandler {
        override val tvName get() = this@TvLink.tvName
        override fun onSearch(query: String, phone: String) = main.post { vm.remoteSearch(query, phone) }.let { }
        override fun onPlay(key: String, title: String, coverUrl: String?, phone: String) = main.post { vm.playKey(key, title, coverUrl, phone) }.let { }
        override fun onPairRequest(phone: String) {
            _code.value = guard.code
            _pairRequest.value = phone
        }

        override fun onPaired(token: String, phone: String) {
            _pairRequest.value = null
            prefs.edit().putString("t:$token", phone).apply()
            _paired.value = guard.paired
            _code.value = guard.code
            main.post { vm.notifyUser("$phone is now connected") }
        }

        override fun onSetup(fields: Map<String, Any?>): String? {
            fun s(k: String) = (fields[k] as? String)?.trim().orEmpty()
            val guide = s("guideKey").takeIf { it.isNotEmpty() }
            // Only the streaming guide key: leave the server settings as they are.
            if (guide != null && s("host").isEmpty() && vm.config != null) {
                main.post {
                    vm.saveGuide(guide, s("country").takeIf { it.length == 2 })
                    _pairRequest.value = null
                    _code.value = guard.code
                    vm.notifyUser("Streaming guide key received")
                }
                return null
            }
            val host = s("host").ifEmpty { return "Server host is required" }
            val port = s("port").ifEmpty { "563" }.toIntOrNull()?.takeIf { it in 1..65535 } ?: return "Port must be a number"
            val user = s("user").ifEmpty { return "Username is required" }
            val pass = (fields["pass"] as? String).orEmpty().ifEmpty { return "Password is required" }
            val conns = s("conns").ifEmpty { "10" }.toIntOrNull()?.coerceIn(1, 50) ?: 10
            val tls = fields["tls"] != false
            val indexer = s("apiKey").takeIf { it.isNotEmpty() }?.let { IndexerConfig(s("indexerUrl").ifEmpty { "https://api.nzbgeek.info/" }, it) }
            main.post {
                vm.saveSettings(NntpConfig(host, port, tls, user, pass, conns), indexer)
                if (guide != null) vm.saveGuide(guide, s("country").takeIf { it.length == 2 })
                _pairRequest.value = null
                _code.value = guard.code
                vm.notifyUser("Settings received from your phone")
            }
            return null
        }
    }

    /** The page the QR code opens on the phone. Plain HTML/JS, no external resources. */
    private fun setupPage(): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Set up $tvName</title>
<style>
 body{font-family:system-ui,sans-serif;background:#080616;color:#f4f4ff;margin:0;padding:24px;max-width:520px}
 h1{font-size:24px;margin:0 0 4px} p{color:#a9abd9;margin:0 0 20px}
 label{display:block;font-size:13px;color:#a9abd9;margin:14px 0 6px}
 input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #24236a;background:#1a1953;color:#fff;font-size:16px}
 .row{display:flex;gap:12px}.row>div{flex:1}
 button{margin-top:24px;width:100%;padding:16px;border:0;border-radius:28px;background:#2f2fe4;color:#fff;font-size:18px;font-weight:700}
 #msg{margin-top:16px;font-size:15px} .ok{color:#46d369} .bad{color:#ff6b6b}
</style></head><body>
<h1>Set up $tvName</h1><p>Type your details here; they go straight to the TV on your home Wi‑Fi.</p>
<form id="f">
 <label>Code shown on the TV</label><input name="code" inputmode="numeric" autocomplete="one-time-code" required>
 <label>Usenet server</label><input name="host" value="news.newsdemon.com" required>
 <div class="row"><div><label>Port</label><input name="port" value="563" inputmode="numeric"></div><div><label>Connections</label><input name="conns" value="10" inputmode="numeric"></div></div>
 <label>Username</label><input name="user" autocomplete="username" required>
 <label>Password</label><input name="pass" type="password" autocomplete="current-password" required>
 <label>Indexer URL (optional)</label><input name="indexerUrl" value="https://api.nzbgeek.info/">
 <label>Indexer API key (optional)</label><input name="apiKey" autocomplete="off">
 <label>Streaming guide key (optional, movieofthenight.com)</label><input name="guideKey" autocomplete="off">
 <label>Country for the guide (optional, e.g. in)</label><input name="country" maxlength="2">
 <button>Send to TV</button><div id="msg"></div>
</form>
<script>
 const f=document.getElementById('f'),m=document.getElementById('msg');
 const c=new URLSearchParams(location.search).get('c'); if(c) f.code.value=c;
 f.onsubmit=async e=>{e.preventDefault();m.textContent='Sending…';m.className='';
  const d=Object.fromEntries(new FormData(f)); d.tls=true;
  try{const r=await fetch('/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)});
   const j=await r.json(); if(r.ok){m.textContent='Done! Look at your TV.';m.className='ok';f.reset();}
   else{m.textContent=j.error||('Error '+r.status);m.className='bad';}}catch(x){m.textContent='Could not reach the TV.';m.className='bad';}};
</script></body></html>
""".trimIndent()
}

/** QR as a bitmap (black on white, with quiet zone). */
fun qrBitmap(text: String, size: Int = 480): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** TV Settings card: scan to set up / pair a phone, plus the list of paired phones. */
@Composable
fun TvLinkPanel(vm: MainViewModel) {
    val link = vm.tvLink ?: return
    val code by link.code.collectAsState()
    val paired by link.paired.collectAsState()
    // Codes rotate after use; keep the one shown current.
    LaunchedEffect(Unit) { while (true) { link.refreshCode(); delay(2_000) } }
    val ip = remember { link.address() }
    val url = ip?.let { "http://$it:${LinkProtocol.PORT}/setup?c=$code" }
    val qr = remember(url) { url?.let { qrBitmap(it).asImageBitmap() } }
    val p = DarkPalette

    Row(Modifier.background(p.surface, RoundedCornerShape(16.dp)).padding(22.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
        qr?.let { Image(it, "Setup QR code", Modifier.size(180.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set up or connect your phone", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Scan with your phone's camera to type your server details there.", color = p.muted, fontSize = 15.sp)
            Text("In the phone app: Settings → Connect a TV, then enter:", color = p.muted, fontSize = 15.sp)
            Text(code.chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            url?.let { Text(it.substringBefore("?"), color = p.muted, fontSize = 13.sp) } ?: Text("Connect the TV to Wi‑Fi to use your phone.", color = p.accent)
            if (paired.isNotEmpty()) {
                Text("Connected phones", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                paired.forEach { (token, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(name, fontSize = 15.sp)
                        TvButton("Remove", { link.unpair(token) })
                    }
                }
            }
        }
    }
}

/** Full-screen card: "<phone> wants to connect" + the code, over any TV screen. Back closes it. */
@Composable
fun TvPairRequestOverlay(vm: MainViewModel) {
    val link = vm.tvLink ?: return
    val phone by link.pairRequest.collectAsState()
    val code by link.code.collectAsState()
    val p = DarkPalette
    val dismiss = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.animation.AnimatedVisibility(phone != null, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        androidx.activity.compose.BackHandler { link.dismissPairRequest() }
        LaunchedEffect(phone) {
            if (phone != null) {
                delay(120) // let the card lay out first
                runCatching { dismiss.requestFocus() }
                delay(120_000)
                link.dismissPairRequest()
            }
        }
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(Color(0xE6000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // Focus stays on the card while it's up; the screen behind can't be reached with the remote.
                Modifier.focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }.focusGroup()
                    .background(p.surface, RoundedCornerShape(24.dp)).padding(horizontal = 56.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${phone ?: "A phone"} wants to connect", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Type this code on your phone", color = p.muted, fontSize = 18.sp)
                Text(code.chunked(3).joinToString("  "), fontSize = 72.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, modifier = Modifier.padding(vertical = 8.dp))
                TvButton("Not now", { link.dismissPairRequest() }, Modifier.focusRequester(dismiss))
            }
        }
    }
}

