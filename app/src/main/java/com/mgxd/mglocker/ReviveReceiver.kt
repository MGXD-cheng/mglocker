package com.mgxd.mglocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 系统级广播复活通道（v2.0 核心增强）。
 *
 * 背景：TCL 电视 ROM 的省电策略会吞掉 AlarmManager 心跳闹钟，导致进程被杀后无法复活
 * （实测现象：锁定界面停在桌面超过 60 秒 + 8080 控制台不可访问 → 心跳通道失效）。
 *
 * 方案：改走"系统广播"复活——这些广播由系统时钟/事件驱动，ROM 无法单独禁用：
 *  ① TIME_TICK：系统每分钟必然发送的时钟广播（Android 6.0 无隐式广播限制，可静态注册）；
 *  ② SCREEN_ON / SCREEN_OFF：开屏/关屏瞬间，屏幕一亮立即复活并弹回锁定界面；
 *  ③ USER_PRESENT：用户唤醒屏幕时；
 *  ④ CONNECTIVITY_CHANGE：网络切换时（Android 6.0 仍会发送给静态注册接收器）。
 *
 * 收到任一广播（进程被杀后系统会自动重建进程来投递）：
 *  ① 复活 GuardService（8080 控制台 + 轮询弹回）；
 *  ② 锁定中且主界面不在前台 → 自动拉起锁定界面；
 *  ③ 续期心跳闹钟（保留作为辅助通道）。
 * 全部为幂等轻量操作（<5ms），平时零开销。
 */
class ReviveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_USER_PRESENT,
            CONNECTIVITY_ACTION -> {
                // ① 复活守护服务（HTTP 控制台 + 轮询弹回），幂等：已存在则不重复创建
                try {
                    context.startService(Intent(context, GuardService::class.java))
                } catch (_: Exception) {
                }

                // ② 锁定中且主界面不在前台（进程刚复活或用户已离开）→ 拉起锁定界面
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

                // ③ 续期心跳闹钟（辅助通道，即便被 ROM 吞掉也无妨）
                HeartbeatReceiver.schedule(context)
            }
        }
    }

    companion object {
        /** Android 7.0 起系统不再向静态接收器发送该广播，但目标电视为 Android 6.0，仍有效 */
        private const val CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE"
    }
}
