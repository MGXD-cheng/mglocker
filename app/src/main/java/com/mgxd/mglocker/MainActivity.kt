package com.mgxd.mglocker

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 锁定状态：界面实时诊断，一眼看出哪里没锁住 */
enum class LockState(
    val title: String,
    val hint: String,
    val color: Color
) {
    UNKNOWN("正在锁定…", "正在进入锁定模式", Color(0xFF90CAF9)),
    LOCKED("已锁定", "硬锁已生效，仅可通过 ADB 解除", Color.White),
    SOFT_LOCKED(
        "已锁定",
        "设备管理员已激活：不可卸载、不可强停，仅 ADB 可解除\n本电视 ROM 不支持 LockTask 硬锁，按 Home 会自动弹回本页",
        Color.White
    ),
    PINNED("已锁定", "屏幕固定已生效：Home键与最近任务被锁定", Color.White),
    ADMIN_NOT_ACTIVE(
        "未激活设备管理员",
        "点击下方按钮一键激活\n若无法跳转，请用遥控器找：设置 → 安全 → 设备管理应用 → 启用 MG locker\n否则 Home 键可以退出锁定",
        Color(0xFFFFB74D)
    ),
    PIN_REQUIRED(
        "需要开启屏幕固定",
        "点击下方按钮去开启『屏幕固定』（设置 → 安全 → 屏幕固定）\n开启后重新进入本应用将自动锁定，Home键与最近任务全部失效",
        Color(0xFFFFF176)
    ),
    WHITELIST_PENDING(
        "白名单设置中",
        "正在重试加入锁任务白名单…\n若长时间停留在此状态请重新激活管理员",
        Color(0xFFFFF176)
    ),
    LOCK_FAILED(
        "锁定失败",
        "管理员已激活但未能进入锁定模式\n请检查系统设置后重启应用",
        Color(0xFFEF9A9A)
    )
}

class MainActivity : ComponentActivity() {

    companion object {
        /** 远程解锁广播（由 HTTP 控制服务器发出） */
        const val ACTION_UNLOCK = "com.mgxd.mglocker.ACTION_UNLOCK"

        /** 允许退出标志：HTTP /unlock 置 true 后，Home/返回不再自动弹回 */
        @Volatile
        var allowExit = false

        /** 主界面是否在前台（onResume=true / onStop=false），GuardService 据此决定是否拉起 */
        @Volatile
        var isForeground = false

        /** 主界面最后一次在前台的时刻（onResume 刷新）。GuardService 兜底：即使 isForeground 残留 true，超过 4 秒未刷新也会强制拉起 */
        @Volatile
        var lastActivitySeen = 0L

        /** 正在跳转系统设置页（管理员激活/屏幕固定）——期间禁止抢回前台（GuardService 轮询也读取此标志） */
        @Volatile
        var navigatingAway = false
    }

    private var lockState by mutableStateOf(LockState.UNKNOWN)
    /** 诊断信息：setLockTaskPackages / startLockTask 的真实异常，显示在界面上 */
    private var errorDetail by mutableStateOf("")

    /** 本机局域网 IP：锁定界面实时显示，方便从任意设备访问 8080 控制台（http://IP:8080） */
    private var ipAddress by mutableStateOf(getLocalIpAddress())

    /** IP 定时刷新：开机瞬间 WiFi 可能未就绪，连上后自动补上正确 IP（每 5 秒重试直到拿到） */
    private val ipHandler = Handler(Looper.getMainLooper())
    private val ipRefreshRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            ipAddress = getLocalIpAddress()
            if (ipAddress.isBlank()) {
                ipHandler.postDelayed(this, 5000)
            }
        }
    }

    /** 远程解锁广播接收器：收到后解除 LockTask 并退出锁定界面（不自动弹回） */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            allowExit = true
            // 关键：先解除 Lock Task 硬锁，否则系统拦截 Home，残留实例会"解不开"
            try {
                stopLockTask()
            } catch (_: Exception) {
            }
            // finishAndRemoveTask：彻底清掉任务栈，防止残留实例导致下次 /lock 复用旧实例
            finishAndRemoveTask()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 竞态修复（v2.3 强化）：
        // 解锁状态下（allowExit=true）只有"用户从桌面图标显式打开"才视为重新锁定；
        // 其余一切内部拉起（GuardService 轮询 / 广播复活 / ROM 恢复前台应用）一律保持解锁退出，
        // 绝不重新上锁 —— 根治"秘技/控制台解锁后立刻被重新锁定"。
        if (allowExit && !isLauncherOpen(intent)) {
            finishAndRemoveTask()
            return
        }
        allowExit = false

        // 注册远程解锁广播（HTTP 控制服务器 /unlock 触发）
        val unlockFilter = IntentFilter(ACTION_UNLOCK)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, unlockFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(unlockReceiver, unlockFilter)
        }

        // 屏幕常亮：锁定状态下不允许息屏打断
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式：隐藏状态栏 + 导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // 拦截返回键：任何返回操作全部无效
        onBackPressedDispatcher.addCallback(this) {
            // 拒绝返回 —— 这就是个 Feature，不是 Bug 😏
        }

        // 首次尝试进入锁定
        ensureLocked()

        // 启动守护服务（常驻前台，提升进程优先级，保证 Home 弹回及时）
        try {
            startService(Intent(this, GuardService::class.java))
        } catch (_: Exception) {
        }

        setContent {
            LockScreen(
                state = lockState,
                errorDetail = errorDetail,
                ipAddress = ipAddress,
                onActivateAdmin = ::activateAdmin,
                onEnablePinning = ::enablePinning
            )
        }
    }

    /**
     * 跳转系统"设备管理员激活"页（三级 fallback，兼容各种电视 ROM）：
     * ① 标准激活页（带说明，最理想）
     * ② 直接打开"设备管理应用"列表页
     * ③ 安全设置页（用户手动找"设备管理应用"）
     * 全部失败则界面保持文字引导。
     */
    private fun activateAdmin() {
        // 跳转系统设置页期间禁止抢回前台，否则授权页会被锁屏界面盖住
        navigatingAway = true
        // ① 标准激活页：带激活说明，确认后自动硬锁
        val addIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                AdminReceiver.component(this@MainActivity)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后 MG locker 将进入硬锁模式：Home键、最近任务、返回键全部失效，仅可通过 ADB 解除。"
            )
        }
        if (addIntent.resolveActivity(packageManager) != null) {
            try {
                startActivity(addIntent)
                return
            } catch (_: Exception) {
                // 尝试失败，继续 fallback
            }
        }

        // ② 设备管理应用列表页（API 21+，多数 ROM 支持）
        // 注意：Settings.ACTION_DEVICE_ADMIN_SETTINGS 在部分 SDK 被隐藏，直接用字符串常量
        try {
            val listIntent = Intent("android.settings.DEVICE_ADMIN_SETTINGS")
            if (listIntent.resolveActivity(packageManager) != null) {
                startActivity(listIntent)
                Toast.makeText(this, "请在列表中找到 MG locker 并启用", Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Exception) {
            // 继续 fallback
        }

        // ③ 安全设置页（最后兜底，手动找"设备管理应用"）
        try {
            val securityIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            if (securityIntent.resolveActivity(packageManager) != null) {
                startActivity(securityIntent)
                Toast.makeText(this, "请手动进入：安全 → 设备管理应用 → 启用 MG locker", Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Exception) {
            // 兜底失败，保持界面提示
        }

        Toast.makeText(this, "跳转失败，请按屏幕提示手动操作", Toast.LENGTH_LONG).show()
        lockState = LockState.ADMIN_NOT_ACTIVE
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        lastActivitySeen = SystemClock.uptimeMillis()
        // 刷新本机 IP（DHCP 可能变化），保证锁定界面显示的控制台地址始终可用
        ipAddress = getLocalIpAddress()
        // 从系统设置页返回，复位跳转标志
        navigatingAway = false
        // 残留实例兜底：解锁后若存在未收到广播的实例，回到前台即自动退出
        if (allowExit) {
            finishAndRemoveTask()
            return
        }
        // 每次回到前台都重新确保锁定：
        // 弥补部分 ROM 在管理员激活瞬间 setLockTaskPackages 会失败的时机问题
        ensureLocked()
        // 开机/解锁回到前台时刷新 IP（WiFi 未就绪则启动定时重试）
        ipHandler.removeCallbacks(ipRefreshRunnable)
        if (ipAddress.isBlank()) {
            ipHandler.post(ipRefreshRunnable)
        }
    }

    /**
     * 第三枪：onPause 是最早的离开信号（先于 onStop）。
     * 锁定中失去前台（Home/切走/待机）立即弹回；跳转系统设置页（navigatingAway）与解锁状态除外。
     */
    override fun onPause() {
        super.onPause()
        if (!allowExit && !navigatingAway) bringBackNow()
    }

    /**
     * singleTask 复用实例时会走这里（不走 onCreate）。
     * 必须复位退出标志并重新进入锁定，防止远程解锁后 /lock 复用旧实例导致"假锁定"。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 竞态兜底（v2.3 强化）：解锁状态下，非"用户桌面显式打开"的一切拉起一律保持解锁退出
        if (allowExit && !isLauncherOpen(intent)) {
            finishAndRemoveTask()
            return
        }
        allowExit = false
        ensureLocked()
    }

    /** 判定是否为用户从桌面图标显式打开（MAIN + LAUNCHER）。其余拉起（内部守护/ROM 恢复）视为非用户主动 */
    private fun isLauncherOpen(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
    }

    /**
     * 获取本机局域网 IPv4 地址（优先 192.168/10/172 私有网段，兼容 wlan0/eth0 等网卡）。
     * 锁定界面用它显示 8080 控制台地址，方便远程访问。
     */
    private fun getLocalIpAddress(): String {
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
            // 获取失败则返回空，界面不显示 IP 行
        }
        return fallback
    }

    /**
     * 完整锁定链路：
     * ① Lock Task 硬锁（需 Device Owner 白名单，部分 ROM 不支持则降级）
     * ② 降级为准锁机：设备管理员防卸载/强停 + Home 自动弹回
     * 所有异常写入 errorDetail 显示在界面上，用于远程诊断。
     */
    private fun ensureLocked() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = AdminReceiver.component(this)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // 已在锁定模式（硬锁），防抖不再重复触发
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            lockState = LockState.LOCKED
            return
        }

        // 管理员未激活 → 无法防卸载/强停，提示激活
        if (!dpm.isAdminActive(component)) {
            errorDetail = "isAdminActive=false（管理员未激活）"
            lockState = LockState.ADMIN_NOT_ACTIVE
            return
        }

        // ===== 路径 A：尝试 Lock Task 硬锁（需 Device Owner 白名单）=====
        try {
            dpm.setLockTaskPackages(component, arrayOf(packageName))
            errorDetail = ""
        } catch (e: Exception) {
            errorDetail = "setLockTaskPackages: ${e.javaClass.simpleName}: ${e.message}"
        }
        if (dpm.isLockTaskPermitted(packageName)) {
            try {
                startLockTask()
                // 验证是否真的锁定（防假锁定）
                if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                    lockState = LockState.LOCKED
                } else {
                    lockState = LockState.WHITELIST_PENDING
                }
                return
            } catch (e: Exception) {
                errorDetail = "startLockTask: ${e.javaClass.simpleName}: ${e.message}"
                lockState = LockState.LOCK_FAILED
                return
            }
        }

        // ===== 路径 B：准锁机（ROM 不支持 LockTask 时的降级方案）=====
        if (errorDetail.isBlank()) {
            errorDetail = "此 ROM 的 LockTask 仅认 Device Owner，已启用准锁机：Home 键自动弹回"
        }
        lockState = LockState.SOFT_LOCKED
    }

    // ==================== Home 自动弹回机制 ====================

    /** 300ms 兜底：若仍无焦点则再补一枪（应对各 ROM 启动延迟） */
    private val relockRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (window != null && window!!.decorView.hasWindowFocus()) return@Runnable
        launchSelf()
    }

    /**
     * 立即抢回前台。onUserLeaveHint / onStop 各调一次（两枪均处于 Activity
     * 前台生命周期回调内，系统允许启动），300ms 后再补一枪兜底。
     * 不使用防抖挡板：保证 Home 后快速打开其他 App 时，onStop 那枪不会被吞掉。
     */
    private fun bringBackNow() {
        launchSelf()
        window?.decorView?.removeCallbacks(relockRunnable)
        window?.decorView?.postDelayed(relockRunnable, 300)
    }

    private fun launchSelf() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // 内部弹回也带 remote_lock：解锁竞态下保持解锁退出
                putExtra("remote_lock", true)
            })
        } catch (_: Exception) {
        }
    }

    /**
     * 跳转系统"屏幕固定"设置页（Android 6.0+ 通用，无需管理员）。
     */
    private fun enablePinning() {
        // 跳转系统设置页期间禁止抢回前台
        navigatingAway = true
        try {
            val pinIntent = Intent("android.settings.SCREEN_PINNING_SETTINGS")
            if (pinIntent.resolveActivity(packageManager) != null) {
                startActivity(pinIntent)
                Toast.makeText(this, "请开启『屏幕固定』开关", Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Exception) {
            // 继续 fallback
        }
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            Toast.makeText(this, "请进入：安全 → 屏幕固定 → 开启", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "请手动进入设置 → 安全 → 屏幕固定 开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 残留实例兜底：解锁后回到前台的实例一律退出
            if (allowExit) {
                finishAndRemoveTask()
                return
            }
            // 每次获得焦点：重新隐藏系统栏 + 确认仍在锁定模式
            hideSystemBars()
            ensureLocked()
        } else {
            // 第四枪：失焦即弹回（Home、切走、待机等都会触发），比生命周期回调更灵敏
            if (!allowExit && !navigatingAway) bringBackNow()
        }
    }

    /**
     * 用户尝试离开当前界面时（软锁兜底），立即重新进入锁定模式。
     * 硬锁模式下系统会拦截离开动作，此回调作为第二道防线。
     * 注意：跳转系统设置页（管理员激活/屏幕固定）期间允许离开，避免抢回前台盖住设置页。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 远程解锁期间或正在跳转系统设置期间允许离开，否则 Home 键立即抢回前台
        if (!allowExit && !navigatingAway) bringBackNow()
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
        // 双保险：若 onUserLeaveHint 被 ROM 忽略，onStop 后同样触发弹回
        // 注：Activity 后台 startActivity 可能被 ROM 拦截，真正的兜底是 GuardService 轮询
        if (!allowExit && !navigatingAway) bringBackNow()
    }

    override fun onDestroy() {
        ipHandler.removeCallbacks(ipRefreshRunnable)
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {
        }
        window?.decorView?.removeCallbacks(relockRunnable)
        super.onDestroy()
    }

    /**
     * 按键兜底拦截。
     * 注意：HOME 由系统在 Lock Task 模式下直接拦截，Activity 收不到该按键；
     * 这里拦截的是 BACK / APP_SWITCH 等在未锁定瞬间的漏网按键。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 秘技检测：上上下下左左右右（方向键各两次）触发解锁
        if (event.action == KeyEvent.ACTION_DOWN && detectKonami(event.keyCode)) {
            return true
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_INPUT,
            KeyEvent.KEYCODE_TV_POWER,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU,
            KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                // 全部消费，绝不传递给系统 —— 遥控器按烂了也出不去
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** Konami Code：上上下下 左左右右（↑↑↓↓←→←→） */
    private val konamiSequence = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT
    )
    private var konamiIndex = 0

    private fun detectKonami(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP &&
            keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
            keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            konamiIndex = 0
            return false
        }
        konamiIndex = if (keyCode == konamiSequence[konamiIndex]) konamiIndex + 1 else 0
        if (konamiIndex == konamiSequence.size) {
            konamiIndex = 0
            unlockByKonami()
            return true
        }
        return false
    }

    /** 遥控器秘技解锁：等效于远程 /unlock */
    private fun unlockByKonami() {
        allowExit = true
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        Toast.makeText(this, "🎮 秘技发动！已解锁", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }
}

@Composable
fun LockScreen(
    state: LockState,
    errorDetail: String,
    ipAddress: String,
    onActivateAdmin: () -> Unit,
    onEnablePinning: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14141E)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "\uD83D\uDD12",
                fontSize = 96.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = state.title,
                color = state.color,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold
            )
            // 显示本机 IP 与控制台地址：远程解锁/查看状态直接用手机访问即可
            if (ipAddress.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "🌐 控制台 http://$ipAddress:8080",
                    color = Color(0xFF80CBC4),
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = state.hint,
                color = Color(0xFF7A7A8E),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            if (errorDetail.isNotBlank() && state != LockState.UNKNOWN) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "诊断: $errorDetail",
                    color = Color(0xFFFF8A80),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            if (state == LockState.ADMIN_NOT_ACTIVE) {
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onActivateAdmin,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFB74D),
                        contentColor = Color(0xFF14141E)
                    )
                ) {
                    Text(
                        text = "一键激活设备管理员",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            if (state == LockState.PIN_REQUIRED) {
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onEnablePinning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFF176),
                        contentColor = Color(0xFF14141E)
                    )
                ) {
                    Text(
                        text = "去开启屏幕固定",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}