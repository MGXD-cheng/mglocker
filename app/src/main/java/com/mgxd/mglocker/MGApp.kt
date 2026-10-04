package com.mgxd.mglocker

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

/**
 * 应用入口（v2.5 新增）。
 *
 * Debug 包启用 StrictMode：把主线程磁盘读写、网络请求、慢调用、Activity/资源泄漏
 * 及时打到 logcat（tag: StrictMode），防止后续迭代不知不觉引入 ANR / 泄漏风险。
 * Release 包不做任何检查——零运行时开销。
 *
 * 注：用 FLAG_DEBUGGABLE 判定而非 BuildConfig——AGP 8+ 默认不生成 BuildConfig 类。
 */
class MGApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
            @Suppress("DEPRECATION")
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
    }
}