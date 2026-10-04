package com.mgxd.mglocker

import android.content.Context
import android.content.SharedPreferences
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
 *
 * v2.5 性能优化：
 * ① SharedPreferences 实例懒加载缓存（避免每次 getSharedPreferences 的锁开销）；
 * ② 所有读值走 @Volatile 内存缓存——GuardService 每 500ms 一次的读取从
 *    "每次进 SharedPreferences" 降级为一次字段读（同进程内所有写都经本类，缓存可靠）；
 * ③ 时间段起止解析结果缓存，避免每分钟 split/substring 的字符串分配；
 * ④ 提供 saveAll() 批量保存：控制台一次提交只做一次 edit().apply()（原为 5 次）。
 */
object SettingsStore {
    private const val PREFS = "locker_settings"
    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_SCHEDULED_LOCK = "scheduled_lock"
    private const val KEY_SCHEDULED_RANGE_ENABLED = "scheduled_range_enabled"
    private const val KEY_SCHEDULED_RANGE_START = "scheduled_range_start"
    private const val KEY_SCHEDULED_RANGE_END = "scheduled_range_end"

    /** SharedPreferences 实例缓存（applicationContext，进程级生命周期，无泄漏风险） */
    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences =
        prefsRef ?: ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefsRef = it }

    // ==================== 内存值缓存 ====================
    @Volatile private var cLockEnabled: Boolean? = null
    @Volatile private var cScheduledLock: String? = null
    @Volatile private var cRangeEnabled: Boolean? = null
    @Volatile private var cRangeStart: String? = null
    @Volatile private var cRangeEnd: String? = null

    /** 锁定总开关，默认开启 */
    fun isLockEnabled(ctx: Context): Boolean =
        cLockEnabled ?: prefs(ctx).getBoolean(KEY_LOCK_ENABLED, true).also { cLockEnabled = it }

    fun setLockEnabled(ctx: Context, enabled: Boolean) {
        cLockEnabled = enabled
        prefs(ctx).edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    /** 定时锁定时间 "HH:mm"，空串 = 不启用 */
    fun scheduledLockTime(ctx: Context): String =
        cScheduledLock ?: (prefs(ctx).getString(KEY_SCHEDULED_LOCK, "") ?: "")
            .also { cScheduledLock = it }

    fun setScheduledLockTime(ctx: Context, time: String) {
        cScheduledLock = time
        prefs(ctx).edit().putString(KEY_SCHEDULED_LOCK, time).apply()
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
        cRangeEnabled ?: prefs(ctx).getBoolean(KEY_SCHEDULED_RANGE_ENABLED, false)
            .also { cRangeEnabled = it }

    fun setScheduledRangeEnabled(ctx: Context, enabled: Boolean) {
        cRangeEnabled = enabled
        prefs(ctx).edit().putBoolean(KEY_SCHEDULED_RANGE_ENABLED, enabled).apply()
    }

    /** 时间段开始 "HH:mm"，空串 = 未配置 */
    fun scheduledRangeStart(ctx: Context): String =
        cRangeStart ?: (prefs(ctx).getString(KEY_SCHEDULED_RANGE_START, "") ?: "")
            .also { cRangeStart = it }

    fun setScheduledRangeStart(ctx: Context, time: String) {
        cRangeStart = time
        prefs(ctx).edit().putString(KEY_SCHEDULED_RANGE_START, time).apply()
    }

    /** 时间段结束 "HH:mm"，空串 = 未配置 */
    fun scheduledRangeEnd(ctx: Context): String =
        cRangeEnd ?: (prefs(ctx).getString(KEY_SCHEDULED_RANGE_END, "") ?: "")
            .also { cRangeEnd = it }

    fun setScheduledRangeEnd(ctx: Context, time: String) {
        cRangeEnd = time
        prefs(ctx).edit().putString(KEY_SCHEDULED_RANGE_END, time).apply()
    }

    /**
     * 批量保存（v2.5）：控制台一次提交单次 edit().apply()，
     * 避免 5 次独立 apply 累积 QueuedWork 写入压力。
     */
    fun saveAll(
        ctx: Context,
        lockEnabled: Boolean,
        scheduledLock: String,
        rangeEnabled: Boolean,
        rangeStart: String,
        rangeEnd: String
    ) {
        cLockEnabled = lockEnabled
        cScheduledLock = scheduledLock
        cRangeEnabled = rangeEnabled
        cRangeStart = rangeStart
        cRangeEnd = rangeEnd
        prefs(ctx).edit()
            .putBoolean(KEY_LOCK_ENABLED, lockEnabled)
            .putString(KEY_SCHEDULED_LOCK, scheduledLock)
            .putBoolean(KEY_SCHEDULED_RANGE_ENABLED, rangeEnabled)
            .putString(KEY_SCHEDULED_RANGE_START, rangeStart)
            .putString(KEY_SCHEDULED_RANGE_END, rangeEnd)
            .apply()
    }

    // ==================== 时间段解析结果缓存（避免每分钟字符串分配） ====================
    @Volatile private var parsedStartSrc: String = "\u0000"
    @Volatile private var parsedStartMin: Int = -1
    @Volatile private var parsedEndSrc: String = "\u0000"
    @Volatile private var parsedEndMin: Int = -1

    /** "HH:mm" → 当日分钟数；非法返回 -1（带来源串缓存，串不变不重复解析） */
    private fun minuteOf(src: String, isStart: Boolean): Int {
        if (isStart) {
            if (src != parsedStartSrc) {
                parsedStartSrc = src
                parsedStartMin = parseHmToMinute(src)
            }
            return parsedStartMin
        }
        if (src != parsedEndSrc) {
            parsedEndSrc = src
            parsedEndMin = parseHmToMinute(src)
        }
        return parsedEndMin
    }

    private fun parseHmToMinute(s: String): Int {
        val h = s.substringBefore(":").toIntOrNull() ?: return -1
        val m = s.substringAfter(":").toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
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
        val startMin = minuteOf(scheduledRangeStart(ctx), isStart = true)
        val endMin = minuteOf(scheduledRangeEnd(ctx), isStart = false)
        if (startMin < 0 || endMin < 0) return false
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return when {
            startMin == endMin -> true                       // 全天
            startMin < endMin -> nowMin >= startMin && nowMin < endMin
            else -> nowMin >= startMin || nowMin < endMin    // 跨午夜
        }
    }
}