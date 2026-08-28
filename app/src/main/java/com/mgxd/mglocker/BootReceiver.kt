package com.mgxd.mglocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：电视重启后自动进入锁定界面。
 * 防止通过"重启大法"绕过锁定。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 先启动守护服务（HTTP 控制台 + 轮询弹回），避免锁定界面启动被延迟时无人值守
            try {
                context.startService(Intent(context, GuardService::class.java))
            } catch (_: Exception) {
            }
            // 开机自动锁定规则（设置持久化，重启后依然生效）：
            // ① 总开关关闭 → 开机不锁定（控制台仍可用，可手动 /lock）
            // ② 总开关开启 + 未设定时 → 开机立即锁定（原行为）
            // ③ 总开关开启 + 已设定时 → 仅到点才锁，未到点等定时触发
            if (SettingsStore.isLockEnabled(context) &&
                (SettingsStore.scheduledLockTime(context).isBlank() || SettingsStore.shouldLockNow(context))
            ) {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(launch)
            }
            // 开机后立即安排心跳保活
            HeartbeatReceiver.schedule(context)
        }
    }
}