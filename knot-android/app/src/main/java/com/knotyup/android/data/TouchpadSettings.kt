package com.knotyup.android.data

import android.content.Context

/** 触摸板设置（本地持久化，SharedPreferences；三个开关）。 */
object TouchpadSettings {
    private const val PREF = "knotyup_touchpad"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_OLED = "oled"
    private const val KEY_PARTICLES = "particles"

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /** 震动反馈（默认开）。 */
    var haptics: Boolean
        get() = prefs?.getBoolean(KEY_HAPTICS, true) ?: true
        set(v) = prefs?.edit()?.putBoolean(KEY_HAPTICS, v)?.apply() ?: Unit

    /** OLED 纯黑模式（默认开；关=深灰背景）。 */
    var oled: Boolean
        get() = prefs?.getBoolean(KEY_OLED, true) ?: true
        set(v) = prefs?.edit()?.putBoolean(KEY_OLED, v)?.apply() ?: Unit

    /** 触碰粒子特效（默认关）。 */
    var particles: Boolean
        get() = prefs?.getBoolean(KEY_PARTICLES, false) ?: false
        set(v) = prefs?.edit()?.putBoolean(KEY_PARTICLES, v)?.apply() ?: Unit
}
