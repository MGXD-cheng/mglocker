package com.mgxd.mglocker

import android.content.Context
import java.util.Calendar

/**
 * 持久化设置（SharedPreferences 落盘，保存后重启依然生效）。
 *
 * 控制台可配置：
 *  - 锁定总开关 lock_enabled：关闭后开机不自动锁定、定时锁定不触发、
 *    进程被杀复活不自动拉起；手动 /lock 仍然有效。
 *  - 定时锁定 scheduled_lock：每天固定时间自动锁定（HH:mm，留空 = 不启用，
 *    保持"开机即锁"的原行为）。
 *  - 时间段锁定 scheduled_range_enabled + scheduled_range_start/end：
 *    独立开关。开启后，处于设定时间段内（支持跨午夜）即自动锁定，
 *    视为手动锁定（不受总开关 lock_enabled 约束，独立生效）。
 */
object SettingsStore {
    private const val PREFS = "locker_settings"
    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_SCHEDULED_LOCK = "scheduled_lock"
    private const val KEY_SCHEDULED_RANGE_ENABLED = "scheduled_range_enabled"
    private const val KEY_SCHEDULED_RANGE_START = "scheduled_range_start"
    private const val KEY_SCHEDULED_RANGE_END = "scheduled_range_end"

    /** 锁定总开关，默认开启 */
    fun isLockEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCK_ENABLED, true)

    fun setLockEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    /** 定时锁定时间 "HH:mm"，空串 = 不启用 */
    fun scheduledLockTime(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULED_LOCK, "") ?: ""

    fun setScheduledLockTime(ctx: Context, time: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCHEDULED_LOCK, time).apply()
    }

    /** 是否到达定时锁定时刻（分钟级匹配，每天循环） */
    fun shouldLockNow(ctx: Context, now: Calendar = Calendar.getInstance()): Boolean {
        val t = scheduledLockTime(ctx)
        if (t.isBlank()) return false
        val parts = t.split(":")
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return now.get(Calendar.HOUR_OF_DAY) == h && now.get(Calendar.MINUTE) == m
    }

    // ==================== 时间段锁定（独立开关，视为手动锁定） ====================

    /** 时间段锁定独立开关，默认关闭（关闭 = 时间段配置无效） */
    fun isScheduledRangeEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCHEDULED_RANGE_ENABLED, false)

    fun setScheduledRangeEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SCHEDULED_RANGE_ENABLED, enabled).apply()
    }

    /** 时间段开始 "HH:mm"，空串 = 未配置 */
    fun scheduledRangeStart(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULED_RANGE_START, "") ?: ""

    fun setScheduledRangeStart(ctx: Context, time: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCHEDULED_RANGE_START, time).apply()
    }

    /** 时间段结束 "HH:mm"，空串 = 未配置 */
    fun scheduledRangeEnd(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULED_RANGE_END, "") ?: ""

    fun setScheduledRangeEnd(ctx: Context, time: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCHEDULED_RANGE_END, time).apply()
    }

    /**
     * 当前时间是否处于设定时间段内（每天循环）。
     *  - start < end  ：同日区间，如 08:00-22:00 → [08:00, 22:00)
     *  - start > end  ：跨午夜区间，如 22:00-06:00 → [22:00, 次日06:00)
     *  - start == end ：视为全天（24 小时都在时间段内）
     * 开关未开启或起止时间非法 → 恒为 false（不生效）。
     */
    fun inScheduledRange(ctx: Context, now: Calendar = Calendar.getInstance()): Boolean {
        if (!isScheduledRangeEnabled(ctx)) return false
        val s = scheduledRangeStart(ctx)
        val e = scheduledRangeEnd(ctx)
        if (s.isBlank() || e.isBlank()) return false
        val startMin = s.substringBefore(":").toIntOrNull()?.let { h ->
            s.substringAfter(":").toIntOrNull()?.let { m -> h * 60 + m }
        } ?: return false
        val endMin = e.substringBefore(":").toIntOrNull()?.let { h ->
            e.substringAfter(":").toIntOrNull()?.let { m -> h * 60 + m }
        } ?: return false
        if (startMin < 0 || endMin < 0 || startMin > 1439 || endMin > 1439) return false
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return when {
            startMin == endMin -> true                       // 全天
            startMin < endMin -> nowMin >= startMin && nowMin < endMin
            else -> nowMin >= startMin || nowMin < endMin    // 跨午夜
        }
    }
}
