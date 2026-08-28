package com.mgxd.mglocker

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * 守护服务：常驻前台，提升进程优先级。
 * 目的：
 * ① 运行 NanoHTTPD 局域网远程控制台（8080）；
 * ② 轮询守护——锁定状态下若 MainActivity 不在前台（用户按 Home 后打开其他 App），
 *    持续尝试从 Service 拉起锁定界面。Android 6.0 从 Service 启动 Activity 无系统级限制，
 *    且轮询可无限重试，弥补 Activity 后台 startActivity 被 TCL ROM 拦截的问题。
 */
class GuardService : Service() {

    private var httpServer: LockerHttpServer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 轮询间隔：TCL 后台启动延迟约 3 秒，500ms 一枪足够及时且不频繁 */
    private val guardRunnable = object : Runnable {
        private var lastCheckedMinute = -1L

        override fun run() {
            // 定时锁定：每分钟检查一次是否到点（分钟变化才查，几乎零开销）
            val nowMinute = System.currentTimeMillis() / 60_000L
            if (nowMinute != lastCheckedMinute) {
                lastCheckedMinute = nowMinute
                // ① 单点定时：每天到点自动锁定，依赖总开关（关闭则暂停）
                val pointDue = SettingsStore.isLockEnabled(this@GuardService) &&
                    SettingsStore.shouldLockNow(this@GuardService)
                // ② 时间段锁定：独立开关，视为手动锁定——不受总开关约束，独立生效
                val rangeDue = SettingsStore.inScheduledRange(this@GuardService)
                if ((pointDue || rangeDue) && MainActivity.allowExit && !MainActivity.navigatingAway) {
                    // 定时到点 / 处于锁定时间段且当前未锁定 → 自动锁定
                    MainActivity.allowExit = false
                    try {
                        startActivity(
                            Intent(this@GuardService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra("remote_lock", true)
                            }
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            // 锁定中且主界面不在前台（用户已尝试离开/打开了其他 App）→ 从 Service 拉起
            // 双保险：isForeground 为 false，或 MainActivity 超过 4 秒未刷新前台时间戳（治 isForeground 残留 true 的隐患）
            val activityGone = SystemClock.uptimeMillis() - MainActivity.lastActivitySeen > 4000L
            // 总开关关闭时不自动弹回（进程被杀复活后静态字段重置，防止"关闭锁定又被锁"）
            if (SettingsStore.isLockEnabled(this@GuardService) &&
                (!MainActivity.isForeground || activityGone) && !MainActivity.allowExit && !MainActivity.navigatingAway) {
                try {
                    startActivity(
                        Intent(this@GuardService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            // 内部拉起必须带 remote_lock 标记：解锁状态下（allowExit=true）MainActivity 识别后保持解锁退出
                            putExtra("remote_lock", true)
                        }
                    )
                } catch (_: Exception) {
                    // 拉起失败不崩溃，下一轮继续
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this)
            .setContentTitle("MG Locker")
            .setContentText("锁定守护运行中")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)

        // 启动局域网远程控制 HTTP 服务器（端口 8080）
        if (httpServer == null) {
            httpServer = LockerHttpServer(this, 8080)
            try {
                httpServer?.start()
            } catch (_: Exception) {
            }
        }

        // 启动轮询守护（幂等：重复 onStartCommand 不会叠加线程）
        handler.removeCallbacks(guardRunnable)
        handler.post(guardRunnable)

        // 注册心跳保活闹钟（辅助通道之一，可能被部分 ROM 省电策略吞掉）
        HeartbeatReceiver.schedule(this)

        // v2.0：START_REDELIVER_INTENT —— 进程被杀后系统保证重建服务并重新投递启动 Intent，
        // 重启保证比 START_STICKY 更硬（配合系统广播复活通道 ReviveReceiver，双重保险）。
        // onStartCommand 不依赖 Intent 内容，因此 intent 为 null / 重新投递均安全。
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        handler.removeCallbacks(guardRunnable)
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        httpServer = null
        super.onDestroy()
    }
}
