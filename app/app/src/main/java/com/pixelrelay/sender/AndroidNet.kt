package com.pixelrelay.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.pixelrelay.core.Clock
import com.pixelrelay.core.NetSnapshot
import com.pixelrelay.core.NetworkState
import com.pixelrelay.core.Proto
import com.pixelrelay.ui.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Base64

class AndroidNetworkState(private val ctx: Context) : NetworkState {
    override fun snapshot(): NetSnapshot {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return NetSnapshot(false, false, false)
        val caps = cm.getNetworkCapabilities(net) ?: return NetSnapshot(false, false, false)
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val unmeteredWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        return NetSnapshot(connected, unmeteredWifi, roaming)
    }
}

/** NSD(`_pixelrelay._tcp`)로 픽셀 주소를 찾는다. */
class NsdFinder(private val ctx: Context) {
    @Suppress("DEPRECATION")
    suspend fun find(timeoutMs: Long = 4000): String? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val result = CompletableDeferred<String?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        result.complete(serviceInfo.host?.hostAddress)
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { result.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { result.await() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}

/**
 * 짝지은 픽셀에 서명된 /status 가 성공하면 연결을 돌려준다.
 * 마지막 IP 를 먼저 시도하고, 안 되면 NSD 로 다시 찾는다.
 * Android 16+ 로컬 네트워크 권한이 필요한 기기에서 권한이 없으면 LAN 을 쓰지 않는다(드라이브만).
 */
class NsdLanConnector(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val client: OkHttpClient = HttpPixelApi.defaultClient(),
) : LanConnector {
    override suspend fun connect(): PixelApi? {
        if (!com.pixelrelay.ui.LocalNetworkPermission.granted(ctx)) return null
        val p = settings.pairing() ?: return null
        val key = Base64.getDecoder().decode(p.keyBase64)
        suspend fun tryHost(h: String): PixelApi? {
            val api = HttpPixelApi(h, Proto.PORT, key, clock, client)
            return try { api.status(); api } catch (e: Exception) { null }
        }
        if (p.host.isNotEmpty()) tryHost(p.host)?.let { return it }
        val found = NsdFinder(ctx).find() ?: return null
        val api = tryHost(found) ?: return null
        settings.savePairing(found, p.keyBase64)
        return api
    }
}
