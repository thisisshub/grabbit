package dev.usenetstream.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import dev.usenetstream.engine.link.LinkClient
import dev.usenetstream.engine.link.LinkProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class FoundTv(val name: String, val host: String, val port: Int)
data class PairedTv(val name: String, val host: String, val port: Int, val token: String)

/**
 * Phone side of the link: finds TVs on the home Wi-Fi (NSD), pairs with the code on the TV, remembers it,
 * and sends search text / "play this". If the TV's address changed, it re-finds it by name.
 */
class PhoneLink(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("link_phone", Context.MODE_PRIVATE)
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found

    private val _tv = MutableStateFlow(load())
    val tv: StateFlow<PairedTv?> = _tv

    val phoneName: String = Build.MODEL ?: "Phone"

    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    // Older Android resolves one service at a time.
    private val toResolve = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    fun startDiscovery() {
        if (discovery != null) return
        multicast = wifi.createMulticastLock("usenetstream-nsd").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve += info
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.value = _found.value.filterNot { it.name == info.serviceName }
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(LinkProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        multicast?.release()
        multicast = null
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val info = toResolve.poll() ?: return resolving.set(false)
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                resolving.set(false)
                resolveNext()
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val host = i.host?.hostAddress
                if (host != null) {
                    val tv = FoundTv(i.serviceName, host, i.port)
                    _found.value = (_found.value.filterNot { it.name == tv.name } + tv)
                    // Our paired TV moved to a new address: follow it.
                    _tv.value?.takeIf { it.name == tv.name && it.host != host }?.let { save(it.copy(host = host, port = tv.port)) }
                }
                resolving.set(false)
                resolveNext()
            }
        })
    }

    /** Makes the TV show its code on screen. */
    suspend fun requestCode(tv: FoundTv): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { LinkClient(tv.host, tv.port).requestPair(phoneName) }
    }

    suspend fun pair(tv: FoundTv, code: String): Result<PairedTv> = withContext(Dispatchers.IO) {
        runCatching {
            val c = LinkClient(tv.host, tv.port)
            val token = c.pair(code.filter(Char::isDigit), phoneName)
            PairedTv(tv.name, tv.host, tv.port, token).also(::save)
        }
    }

    fun forget() {
        prefs.edit().clear().apply()
        _tv.value = null
    }

    suspend fun search(query: String): Result<Unit> = send { it.search(query) }

    suspend fun play(key: String, title: String, coverUrl: String?): Result<Unit> = send { it.play(key, title, coverUrl) }

    private suspend fun send(block: (LinkClient) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val tv = _tv.value ?: return@withContext Result.failure(IllegalStateException("No TV connected"))
        runCatching { block(LinkClient(tv.host, tv.port, tv.token)) }.recoverCatching { e ->
            // Maybe the TV got a new address: look again briefly, then retry once.
            startDiscovery()
            kotlinx.coroutines.delay(2_500)
            val moved = _tv.value ?: throw e
            if (moved.host == tv.host) throw e
            block(LinkClient(moved.host, moved.port, moved.token))
        }
    }

    private fun load(): PairedTv? {
        val host = prefs.getString("host", null) ?: return null
        return PairedTv(prefs.getString("name", "TV")!!, host, prefs.getInt("port", LinkProtocol.PORT), prefs.getString("token", null) ?: return null)
    }

    private fun save(tv: PairedTv) {
        prefs.edit().putString("name", tv.name).putString("host", tv.host).putInt("port", tv.port).putString("token", tv.token).apply()
        _tv.value = tv
    }
}
