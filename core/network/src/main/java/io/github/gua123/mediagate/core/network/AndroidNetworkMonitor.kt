package io.github.gua123.mediagate.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.gua123.mediagate.core.common.AppLog

/**
 * 网络现场监听（**R7**：plan 4.5「`NetworkCallback` 变化即失效并重测」）。
 *
 * 采集三样东西（[NetworkContext]）：
 * - **传输类型**：Wi-Fi / 蜂窝 / 以太网 / VPN（[NetworkCapabilities] 的 TRANSPORT_*）；
 * - **SSID**：仅 Wi-Fi 且**有定位权限**时才拿得到；拿不到就是 null（R6 不为它额外申请权限；
 *   2026-10-25 起只用于界面展示，不参与选路）；
 * - **本机网段**：`LinkProperties.linkAddresses` → `192.168.1.10/24` 这样的 CIDR 串。
 *
 * 每次回调都把 [NetworkContext.revision] +1，界面据此让 60 s 缓存立刻失效。
 *
 * **只能在真机上验证**（ConnectivityManager 回调、Wi-Fi 切换、蜂窝切换）：JVM 侧只保证编译通过。
 *
 * @param context 应用上下文。
 */
class AndroidNetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val _context = MutableStateFlow(NetworkContext(online = false, revision = 0L))

    /** 当前网络现场（可在 Compose / ViewModel 里直接 collect）。 */
    val context: StateFlow<NetworkContext> = _context.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 开始监听（重复调用无副作用）。 */
    fun start() {
        if (callback != null) return
        val manager = connectivity ?: run {
            AppLog.w(TAG, "没有 ConnectivityManager，网络现场保持「未知」")
            return
        }
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            // 2026-11-03 真机：一律回到「当前默认网络」重新读现场，不信回调参数。
            // 旧实现用 registerNetworkCallback(INTERNET) + onLost(null)：任意一条被跟踪网络
            // （例如刚切走的 Wi-Fi、断开的 VPN）onLost 就把全局改写成「未知/离线」，
            // 而新默认网络早已就绪、不会再发 onAvailable ⇒ 界面误报「当前没有网络（未知网络）」，
            // 选路直接 OFFLINE、连接被拦死（真机截图复现）。
            override fun onAvailable(network: Network) = refresh(manager)

            override fun onLost(network: Network) = refresh(manager)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh(manager)

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = refresh(manager)
        }
        callback = networkCallback
        runCatching { manager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { AppLog.w(TAG, "注册默认网络回调失败", it) }
        refresh(manager)
    }

    /** 停止监听（幂等）。 */
    fun stop() {
        val manager = connectivity ?: return
        val current = callback ?: return
        callback = null
        runCatching { manager.unregisterNetworkCallback(current) }
    }

    /** 主动刷新一次（回前台 / 选连接前自愈用）。 */
    fun refreshNow() {
        val manager = connectivity ?: return
        refresh(manager)
    }

    /**
     * 重新读 activeNetwork 的现场；现场没变就不推新值。
     *
     * 不推新值很重要：NetworkContext 变化会触发选路/重建后端，如果每次回调都 +revision，
     * 上层自愈调用（回前台、选连接前）就可能造成「刷新→重算→再刷新」的循环。
     */
    private fun refresh(manager: ConnectivityManager) {
        val previous = _context.value
        val network = manager.activeNetwork
        val next = if (network == null) {
            NetworkContext(
                capability = NetworkCapability.UNKNOWN,
                ssid = null,
                localSubnets = emptySet(),
                online = false,
                revision = previous.revision,
            )
        } else {
            val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            val props = runCatching { manager.getLinkProperties(network) }.getOrNull()
            val capability = capabilityOf(caps)
            NetworkContext(
                capability = capability,
                ssid = if (capability == NetworkCapability.WIFI) currentSsid() else null,
                localSubnets = subnetsOf(props),
                // 2026-11-03：不再要求 VALIDATED——VPN / 无外网的局域网常拿不到 VALIDATED，
                // 误判离线会把「其实能连 NAS」的情况直接拦死；真正的可用性交给协议探测。
                online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                revision = previous.revision,
            )
        }
        if (next == previous) return
        _context.value = next.copy(revision = previous.revision + 1)
    }

    /** 传输类型：以太网 > Wi-Fi > 蜂窝 > VPN（同时在线的组合里取"真实承载"）。 */
    private fun capabilityOf(caps: NetworkCapabilities?): NetworkCapability {
        if (caps == null) return NetworkCapability.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapability.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapability.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapability.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkCapability.VPN
            else -> NetworkCapability.UNKNOWN
        }
    }

    /** 本机网段（CIDR）；IPv6 会去掉 `%wlan0` 之类的 scope 后缀。 */
    private fun subnetsOf(props: LinkProperties?): Set<String> {
        val links = props?.linkAddresses ?: return emptySet()
        return links.mapNotNull { link ->
            val host = link.address?.hostAddress ?: return@mapNotNull null
            val clean = host.substringBefore('%')
            clean + "/" + link.prefixLength
        }.toSet()
    }

    /**
     * 当前 Wi-Fi SSID。
     *
     * Android 13+ 读 SSID 需要定位权限；没有权限时系统返回 `<unknown ssid>`（或抛 SecurityException），
     * 两种情况都归成 null——**不为了一个展示字段去要定位权限**（R6）。
     */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return null
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
        return NetworkContext.normalizeSsid(runCatching { info.ssid }.getOrNull())
    }

    private companion object {
        const val TAG = "core-network"
    }
}
