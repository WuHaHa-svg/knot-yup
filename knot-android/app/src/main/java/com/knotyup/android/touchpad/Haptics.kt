package com.knotyup.android.touchpad

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 触摸板手势震动反馈。
 *
 * 用 VibrationEffect.createPredefined(EFFECT_TICK)：系统预定义效果由厂商针对本机马达
 * （线性马达/转子马达）做过硬件调教，系统还会自动 fallback 到通用模式，
 * 因此不同机型上能得到相近的"短促、清脆"触感——这正是跨机型一致性的标准解法。
 * 不用 createOneShot(ms, amplitude)：振幅绝对值在不同马达上差异极大，无法保证手感一致。
 */
object Haptics {
    @Volatile
    private var vibrator: Vibrator? = null

    fun init(ctx: Context) {
        if (vibrator == null) {
            vibrator = ctx.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** 短促清脆的一下（用于单指长按进入拖动、双指长按触发调度中心）。 */
    fun tick() {
        vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}
