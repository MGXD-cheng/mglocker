package com.mgxd.mglocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 心跳保活（辅助通道）：每 30 秒被 AlarmManager 唤醒一次。
 *
 * v2.0 起主复活通道为 ReviveReceiver（系统广播 TIME_TICK/SCREEN_ON 等，ROM 无法禁用），
 * 本闹钟保留作辅助——部分 ROM 省电策略可能吞掉闹钟，但不影响主通道。
 *
 * 作用：进程被系统回收后，闹钟广播可强制重建进程，确保：
 * ① GuardService（含 8080 控制台）恢复运行；
 * ② 锁定中且主界面不在前台时，自动拉起锁定界面。
 * 30s 间隔：把"守护服务被杀后的无人值守真空期"压缩到最短（电视插电场景耗电可忽略）。
 */
class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_HEARTBEAT = "com.mgxd.mglocker.ACTION_HEARTBEAT"
        private const val INTERVAL_MS = 30_000L
        /** 解锁态 / 关闭锁定时的空闲心跳间隔（5 分钟，非精确闹钟） */
        private const val IDLE_INTERVAL_MS = 300_000L

        /** 注册/续期心跳闹钟（每次心跳后自动续期，形成循环） */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, HeartbeatReceiver::class.java).setAction(ACTION_HEARTBEAT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // v2.5 自适应心跳：
            //  - 锁定中：30s 精确闹钟（setExactAndAllowWhileIdle），把无人值守真空期压到最短；
            //  - 已解锁 / 关闭锁定：降为 5 分钟非精确闹钟（set）——不需要频繁守护，
            //    避免每 30s 一次精确唤醒的耗电（Doze 下由系统合并调度，仍可兜底复活）。
            val guarding = !MainActivity.allowExit && SettingsStore.isLockEnabled(context)
            if (guarding) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    pi
                )
            } else {
                am.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + IDLE_INTERVAL_MS,
                    pi
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT) return

        // 锁定中且主界面不在前台（进程刚复活或用户离开过）→ 拉起锁定界面
        // 总开关关闭时不自动拉起（防止"关闭锁定后进程被杀复活又被锁"）
        if (SettingsStore.isLockEnabled(context) &&
            !MainActivity.allowExit && !MainActivity.isForeground && !MainActivity.navigatingAway) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        // 内部拉起带 remote_lock：解锁状态下保持解锁退出，绝不重新上锁
                        putExtra("remote_lock", true)
                    }
                )
            } catch (_: Exception) {
            }
        }

        // 确保守护服务（HTTP 控制台 + 轮询弹回）在运行
        try {
            context.startService(Intent(context, GuardService::class.java))
        } catch (_: Exception) {
        }

        // 续期下一次心跳
        schedule(context)
    }
}