package com.mgxd.mglocker

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * 局域网 IP 提供器（v2.5 性能优化）。
 *
 * 优化背景：原实现分别在 MainActivity 属性初始化、onResume、每 5 秒重试、
 * 以及 HTTP 服务每次请求时，于调用线程枚举网络接口（NetworkInterface 系统调用）。
 * 主线程上的系统调用会阻塞输入分发（ANR 风险），HTTP 线程上的重复枚举则是纯浪费。
 *
 * 新方案：
 * ① 枚举统一在后台单线程执行（永不阻塞主线程）；
 * ② 结果写入 @Volatile 内存缓存，主线程 / HTTP 线程只读缓存（纳秒级）；
 * ③ 带 30 秒 TTL：过期后由调用方触发后台刷新，读取永远不阻塞。
 */
object IpProvider {

    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var cachedIp: String = ""

    @Volatile
    private var lastFetchAt: Long = 0L

    @Volatile
    private var fetching: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mg-ip-provider").apply { isDaemon = true }
    }

    /** 立即返回缓存值（永不阻塞、永不枚举网卡） */
    fun cached(): String = cachedIp

    /** 缓存是否为空或已过期 */
    fun isStale(): Boolean =
        cachedIp.isEmpty() || SystemClock.elapsedRealtime() - lastFetchAt >= CACHE_TTL_MS

    /**
     * 后台刷新 IP。缓存新鲜时直接回调缓存值（零开销）。
     *
     * @param force     true = 忽略 TTL 强制重新枚举（用于"一直没拿到 IP"的重试场景）
     * @param onResult  结果回调，运行在主线程；仅当本次确实刷新完成（或命中新鲜缓存）时回调
     */
    fun refresh(force: Boolean = false, onResult: ((String) -> Unit)? = null) {
        val now = SystemClock.elapsedRealtime()
        if (!force && !isStale()) {
            onResult?.let { cb -> mainHandler.post { cb(cachedIp) } }
            return
        }
        if (fetching) return // 已有刷新任务在跑，避免重复枚举
        fetching = true
        worker.execute {
            val ip = queryIpBlocking()
            cachedIp = ip
            lastFetchAt = SystemClock.elapsedRealtime()
            fetching = false
            if (onResult != null) {
                mainHandler.post { onResult(ip) }
            }
        }
    }

    /**
     * 阻塞式枚举局域网 IPv4（优先私有网段，兼容 wlan0/eth0 等网卡）。
     * 仅允许在后台线程调用。
     */
    private fun queryIpBlocking(): String {
        var fallback = ""
        try {
            val netIfs = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (netIfs.hasMoreElements()) {
                val ni = netIfs.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                        if (fallback.isEmpty()) fallback = ip
                    }
                }
            }
        } catch (_: Exception) {
            // 获取失败返回空，界面隐藏 IP 行
        }
        return fallback
    }
}
