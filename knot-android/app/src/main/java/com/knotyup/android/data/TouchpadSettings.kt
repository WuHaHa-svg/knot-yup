package com.knotyup.android.data

import android.content.Context

/** 触摸板设置（本地持久化，SharedPreferences）。 */
object TouchpadSettings {
    private const val PREF = "knotyup_touchpad"
    private const val KEY_OLED = "oled"
    private const val KEY_PARTICLES = "particles"
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

    /** 自然滚动（默认开）：手指下滑内容跟手下移；关闭则方向相反（传统滚动）。 */
    var naturalScroll: Boolean
        get() = prefs?.getBoolean(KEY_NATURAL_SCROLL, true) ?: true
        set(v) = prefs?.edit()?.putBoolean(KEY_NATURAL_SCROLL, v)?.apply() ?: Unit
}
