package com.mgxd.mglocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 设备管理员接收器。
 *
 * 激活后立即把本应用加入 Lock Task 白名单，
 * 使 Kiosk 模式成为“硬锁”：用户无法通过 Home / 最近任务退出，
 * 也无法从系统设置中停用本应用，只能通过 ADB 解除。
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, AdminReceiver::class.java)
        try {
            dpm.setLockTaskPackages(component, arrayOf(context.packageName))
        } catch (_: Exception) {
            // 个别 ROM 不允许此时设置，MainActivity 启动时会重试
        }
    }

    companion object {
        /** 获取管理员组件名 */
        fun component(context: Context): ComponentName =
            ComponentName(context, AdminReceiver::class.java)
    }
}
