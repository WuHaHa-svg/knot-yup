package com.knotyup.android.data

import android.content.Context

/** 触摸板设置（本地持久化，SharedPreferences）。 */
object TouchpadSettings {
    private const val PREF = "knotyup_touchpad"
    private const val KEY_OLED = "oled"
    private const val KEY_PARTICLES = "particles"
    private const val KEY_PARTICLE_COLOR = "particle_color"
    private const val KEY_PARTICLE_SIZE = "particle_size"
    private const val KEY_PARTICLE_COUNT = "particle_count"
    private const val KEY_PARTICLE_SPEED = "particle_speed"
    private const val KEY_PARTICLE_TRAIL = "particle_trail"
    private const val KEY_NATURAL_SCROLL = "natural_scroll"

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /** OLED 纯黑模式（默认开；关=深灰背景）。 */
    var oled: Boolean
        get() = prefs?.getBoolean(KEY_OLED, true) ?: true
        set(v) = prefs?.edit()?.putBoolean(KEY_OLED, v)?.apply() ?: Unit

    /** 触碰粒子特效（默认关）。 */
    var particles: Boolean
        get() = prefs?.getBoolean(KEY_PARTICLES, false) ?: false
        set(v) = prefs?.edit()?.putBoolean(KEY_PARTICLES, v)?.apply() ?: Unit

    /** 粒子颜色：random / white / primary / red / blue / gold，默认 random。 */
    var particleColor: String
        get() = prefs?.getString(KEY_PARTICLE_COLOR, "random") ?: "random"
        set(v) = prefs?.edit()?.putString(KEY_PARTICLE_COLOR, v)?.apply() ?: Unit

    /** 粒子大小（dp），范围 0.25~1.5（极小颗粒），默认 0.6。 */
    var particleSize: Float
        get() = prefs?.getFloat(KEY_PARTICLE_SIZE, 0.6f) ?: 0.6f
        set(v) = prefs?.edit()?.putFloat(KEY_PARTICLE_SIZE, v)?.apply() ?: Unit

    /** 每次触碰发射的粒子数，范围 75~225（小而多），默认 150。 */
    var particleCount: Int
        get() = prefs?.getInt(KEY_PARTICLE_COUNT, 150) ?: 150
        set(v) = prefs?.edit()?.putInt(KEY_PARTICLE_COUNT, v)?.apply() ?: Unit

    /** 粒子扩散速度（px/s），范围 120~250，默认 180；ripple 据此算铺满全屏的时长。 */
    var particleSpeed: Float
        get() = prefs?.getFloat(KEY_PARTICLE_SPEED, 180f) ?: 180f
        set(v) = prefs?.edit()?.putFloat(KEY_PARTICLE_SPEED, v)?.apply() ?: Unit

    /** 粒子拖尾（默认关）：沿运动反方向绘制渐隐尾迹。 */
    var particleTrail: Boolean
        get() = prefs?.getBoolean(KEY_PARTICLE_TRAIL, false) ?: false
        set(v) = prefs?.edit()?.putBoolean(KEY_PARTICLE_TRAIL, v)?.apply() ?: Unit

    /** 自然滚动（默认开）：手指下滑内容跟手下移；关闭则方向相反（传统滚动）。 */
    var naturalScroll: Boolean
        get() = prefs?.getBoolean(KEY_NATURAL_SCROLL, true) ?: true
        set(v) = prefs?.edit()?.putBoolean(KEY_NATURAL_SCROLL, v)?.apply() ?: Unit
}
