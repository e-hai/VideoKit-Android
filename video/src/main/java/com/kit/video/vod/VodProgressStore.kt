package com.kit.video.vod

import android.content.Context
import android.content.SharedPreferences

/**
 * 工业级 VOD 播放进度记忆与断点续播存储单例
 * - 本地持久化每个视频 (基于 url 或 key) 的播放进度
 * - 超过 95% 视为完播自动重置
 * - 播放进度小于 3 秒不予触发续播提示
 */
object VodProgressStore {

    private const val PREFS_NAME = "videokit_vod_progress_store"
    private const val KEY_PREFIX_PROGRESS = "progress_"
    private const val MIN_RESUME_THRESHOLD_MS = 3000L // 小于 3 秒不提示
    private const val FINISH_PERCENT_THRESHOLD = 0.95f // 超过 95% 视为完播

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * 保存当前播放进度
     */
    fun saveProgress(key: String, currentPositionMs: Long, durationMs: Long) {
        val sp = prefs ?: return
        if (key.isBlank() || durationMs <= 0) return

        val percent = currentPositionMs.toFloat() / durationMs.toFloat()
        if (percent >= FINISH_PERCENT_THRESHOLD) {
            // 接近完播，清空历史进度
            sp.edit().remove(KEY_PREFIX_PROGRESS + key).apply()
        } else if (currentPositionMs >= MIN_RESUME_THRESHOLD_MS) {
            sp.edit().putLong(KEY_PREFIX_PROGRESS + key, currentPositionMs).apply()
        }
    }

    /**
     * 读取历史保存的断点播放进度 (毫秒)，若无则返回 0
     */
    fun getProgress(key: String): Long {
        val sp = prefs ?: return 0L
        if (key.isBlank()) return 0L
        return sp.getLong(KEY_PREFIX_PROGRESS + key, 0L)
    }

    /**
     * 清除指定视频历史进度
     */
    fun clearProgress(key: String) {
        prefs?.edit()?.remove(KEY_PREFIX_PROGRESS + key)?.apply()
    }
}
