package me.ykrank.s1next.widget.glass

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Fork（tiger-git2022）：玻璃效果的模糊度和不透明度，在「设置 → 常规 → 玻璃效果」里调。
 * 改动后立即生效：玻璃在下一次重绘时读取新值。
 */
object GlassSettings {

    const val KEY_BLUR_DP = "fork_glass_blur_dp"
    const val KEY_OPACITY = "fork_glass_opacity"
    const val DEFAULT_BLUR_DP = 12
    const val DEFAULT_OPACITY = 60

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    var blurDp: Int = DEFAULT_BLUR_DP
        private set

    /** 0..100 */
    @Volatile
    var opacityPercent: Int = DEFAULT_OPACITY
        private set

    // 强引用，避免监听被回收
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == KEY_BLUR_DP || key == KEY_OPACITY) read(p)
    }

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            read(p)
            p.registerOnSharedPreferenceChangeListener(listener)
            prefs = p
        }
    }

    private fun read(p: SharedPreferences) {
        blurDp = p.getInt(KEY_BLUR_DP, DEFAULT_BLUR_DP).coerceIn(0, 40)
        opacityPercent = p.getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0, 100)
    }
}
