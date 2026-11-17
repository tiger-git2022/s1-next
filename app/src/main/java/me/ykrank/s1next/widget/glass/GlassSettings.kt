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
    const val KEY_OPACITY_NIGHT = "fork_glass_opacity_night"
    const val KEY_STATUS_OPACITY = "fork_glass_status_opacity"
    const val KEY_STATUS_OPACITY_NIGHT = "fork_glass_status_opacity_night"
    const val DEFAULT_BLUR_DP = 12
    const val DEFAULT_OPACITY = 60
    const val DEFAULT_OPACITY_NIGHT = 20
    const val DEFAULT_STATUS_OPACITY = 40
    const val DEFAULT_STATUS_OPACITY_NIGHT = 20

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    var blurDp: Int = DEFAULT_BLUR_DP
        private set

    // 不透明度 0..100：白天和夜间分开设（夜间的深灰玻璃叠在深色内容上，需要更通透才看得出玻璃）
    @Volatile
    private var opacityDay = DEFAULT_OPACITY

    @Volatile
    private var opacityNight = DEFAULT_OPACITY_NIGHT

    @Volatile
    private var statusOpacityDay = DEFAULT_STATUS_OPACITY

    @Volatile
    private var statusOpacityNight = DEFAULT_STATUS_OPACITY_NIGHT

    /** 顶栏、悬浮按钮、弹出菜单的不透明度；[dark] 为夜间（玻璃颜色是深色） */
    fun opacity(dark: Boolean): Int = if (dark) opacityNight else opacityDay

    /** 状态栏玻璃条的不透明度 */
    fun statusOpacity(dark: Boolean): Int = if (dark) statusOpacityNight else statusOpacityDay

    // 强引用，避免监听被回收
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key != null && key.startsWith("fork_glass_")) read(p)
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
        opacityDay = p.getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0, 100)
        opacityNight = p.getInt(KEY_OPACITY_NIGHT, DEFAULT_OPACITY_NIGHT).coerceIn(0, 100)
        statusOpacityDay = p.getInt(KEY_STATUS_OPACITY, DEFAULT_STATUS_OPACITY).coerceIn(0, 100)
        statusOpacityNight = p.getInt(KEY_STATUS_OPACITY_NIGHT, DEFAULT_STATUS_OPACITY_NIGHT).coerceIn(0, 100)
    }
}
