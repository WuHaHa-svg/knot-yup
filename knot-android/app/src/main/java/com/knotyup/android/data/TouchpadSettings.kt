package com.knotyup.android.data

import android.content.Context

/** 触摸板设置（本地持久化，SharedPreferences）。 */
object TouchpadSettings {
    private const val PREF = "knotyup_touchpad"
    private const val KEY_OLED = "oled"
    private const val KEY_PARTICLE_STYLE = "particle_style"
    private const val KEY_PARTICLE_COLOR = "particle_color"
    private const val KEY_PARTICLE_SIZE = "particle_size"
    private const val KEY_PARTICLE_COUNT = "particle_count"
    private const val KEY_PARTICLE_SPEED = "particle_speed"
    private const val KEY_PARTICLE_TRAIL = "particle_trail"
    private const val KEY_NATURAL_SCROLL = "natural_scroll"
    private const val KEY_TOUCH_TRAIL = "touch_trail"
    private const val KEY_TRAIL_COLOR = "trail_color"
    private const val KEY_TRAIL_WIDTH = "trail_width_v2"
    private const val KEY_KB_COLOR = "kb_color"
    private const val KEY_KB_CLICK_PARTICLES = "kb_click_particles"
    private const val KEY_KB_MACRO_ENABLED = "kb_macro_enabled"
    private const val KEY_KB_BREATHING = "kb_breathing"

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /** OLED 纯黑模式（默认开；关=深灰背景）。 */
    var oled: Boolean
        get() = prefs?.getBoolean(KEY_OLED, true) ?: true
        set(v) = run { prefs?.edit()?.putBoolean(KEY_OLED, v)?.commit(); Unit }

    /** 粒子风格：off(关闭) / ripple(涟漪) / firework(烟花)，默认 off。 */
    var particleStyle: String
        get() = prefs?.getString(KEY_PARTICLE_STYLE, "off") ?: "off"
        set(v) = run { prefs?.edit()?.putString(KEY_PARTICLE_STYLE, v)?.commit(); Unit }

    /** 粒子颜色：random / white / primary / red / blue / gold，默认 random。 */
    var particleColor: String
        get() = prefs?.getString(KEY_PARTICLE_COLOR, "random") ?: "random"
        set(v) = run { prefs?.edit()?.putString(KEY_PARTICLE_COLOR, v)?.commit(); Unit }

    /** 粒子大小（dp），范围 0.25~1.5（极小颗粒），默认 0.6。 */
    var particleSize: Float
        get() = prefs?.getFloat(KEY_PARTICLE_SIZE, 0.6f) ?: 0.6f
        set(v) = run { prefs?.edit()?.putFloat(KEY_PARTICLE_SIZE, v)?.commit(); Unit }

    /** 每次触碰发射的粒子数，范围 75~225（小而多），默认 150。 */
    var particleCount: Int
        get() = prefs?.getInt(KEY_PARTICLE_COUNT, 150) ?: 150
        set(v) = run { prefs?.edit()?.putInt(KEY_PARTICLE_COUNT, v)?.commit(); Unit }

    /** 粒子扩散速度（px/s），范围 150~2500，默认 180；ripple 据此算铺满全屏的时长。 */
    var particleSpeed: Float
        get() = prefs?.getFloat(KEY_PARTICLE_SPEED, 180f) ?: 180f
        set(v) = run { prefs?.edit()?.putFloat(KEY_PARTICLE_SPEED, v)?.commit(); Unit }

    /** 粒子拖尾（默认开）：沿运动反方向绘制渐隐尾迹。 */
    var particleTrail: Boolean
        get() = prefs?.getBoolean(KEY_PARTICLE_TRAIL, true) ?: true
        set(v) = run { prefs?.edit()?.putBoolean(KEY_PARTICLE_TRAIL, v)?.commit(); Unit }

    /** 自然滚动（默认开）：手指下滑内容跟手下移；关闭则方向相反（传统滚动）。 */
    var naturalScroll: Boolean
        get() = prefs?.getBoolean(KEY_NATURAL_SCROLL, true) ?: true
        set(v) = run { prefs?.edit()?.putBoolean(KEY_NATURAL_SCROLL, v)?.commit(); Unit }

    /** 显示触摸轨迹（默认关）：单指/双指滑动时画出轨迹，纯视觉辅助。 */
    var touchTrail: Boolean
        get() = prefs?.getBoolean(KEY_TOUCH_TRAIL, false) ?: false
        set(v) = run { prefs?.edit()?.putBoolean(KEY_TOUCH_TRAIL, v)?.commit(); Unit }

    /** 触摸轨迹颜色（默认主色），可 random；色板与粒子随机色共用。 */
    var trailColor: String
        get() = prefs?.getString(KEY_TRAIL_COLOR, "primary") ?: "primary"
        set(v) = run { prefs?.edit()?.putString(KEY_TRAIL_COLOR, v)?.commit(); Unit }

    /** 触摸轨迹粗细（px），范围 10~50，默认 20。 */
    var trailWidth: Float
        get() = prefs?.getFloat(KEY_TRAIL_WIDTH, 20f) ?: 20f
        set(v) = run { prefs?.edit()?.putFloat(KEY_TRAIL_WIDTH, v)?.commit(); Unit }

    /** 数字键盘颜色：random(炫彩渐变) / white / primary / red / blue / gold，默认 primary。 */
    var kbColor: String
        get() = prefs?.getString(KEY_KB_COLOR, "primary") ?: "primary"
        set(v) = run { prefs?.edit()?.putString(KEY_KB_COLOR, v)?.commit(); Unit }

    /** 键盘点击溅射粒子（默认关）：点击位置溅射 8~12 个跟随键色的微小粒子。 */
    var kbClickParticles: Boolean
        get() = prefs?.getBoolean(KEY_KB_CLICK_PARTICLES, false) ?: false
        set(v) = run { prefs?.edit()?.putBoolean(KEY_KB_CLICK_PARTICLES, v)?.commit(); Unit }

    /** 数字宏（默认关）：长按数字键触发预定义动作（框架，动作留空回调）。 */
    var kbMacroEnabled: Boolean
        get() = prefs?.getBoolean(KEY_KB_MACRO_ENABLED, false) ?: false
        set(v) = run { prefs?.edit()?.putBoolean(KEY_KB_MACRO_ENABLED, v)?.commit(); Unit }

    /** 键盘呼吸灯（默认开）：颜色明暗周期性呼吸；关闭则常亮。 */
    var kbBreathing: Boolean
        get() = prefs?.getBoolean(KEY_KB_BREATHING, true) ?: true
        set(v) = run { prefs?.edit()?.putBoolean(KEY_KB_BREATHING, v)?.commit(); Unit }
}
