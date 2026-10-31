package com.knotyup.android.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效规范（单一来源）：对齐桌面端 CSS cubic-bezier(.22,1,.36,1)。
 * 页面转场 / 弹窗入场 / 挂载动画一律引用此处，禁止散落魔法数字。
 */
object Motion {
    /** 全局标准缓动（桌面端同款曲线） */
    val Easing = CubicBezierEasing(.22f, 1f, .36f, 1f)

    /** 时长档位（ms） */
    const val FAST = 180
    const val NORMAL = 320
    const val SLOW = 480

    /** 页面转场：进入（轻上滑 + 淡入） */
    fun pageEnter(delay: Int = 0): EnterTransition =
        fadeIn(tween(NORMAL, delayMillis = delay, easing = Easing)) +
            slideInVertically(tween(SLOW, delayMillis = delay, easing = Easing)) { it / 12 }

    /** 页面转场：退出（轻下沉 + 淡出，比进入轻，避免双向抢戏） */
    fun pageExit(): ExitTransition =
        fadeOut(tween(FAST, easing = Easing)) +
            slideOutVertically(tween(NORMAL, easing = Easing)) { -it / 20 }

    /** 页面转场：返回进入（与正向镜像，位移反向且更轻） */
    fun pagePopEnter(): EnterTransition =
        fadeIn(tween(NORMAL, easing = Easing)) +
            slideInVertically(tween(SLOW, easing = Easing)) { -it / 20 }

    /** 页面转场：返回退出 */
    fun pagePopExit(): ExitTransition =
        fadeOut(tween(FAST, easing = Easing)) +
            slideOutVertically(tween(NORMAL, easing = Easing)) { it / 12 }

    /** 弹窗卡片入场：缩放 + 淡入（spring 阻尼 0.88 防回弹溢出裁切） */
    fun dialogEnter(): EnterTransition =
        scaleIn(
            animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f),
            initialScale = 0.82f,
        ) + fadeIn(tween(FAST))

    /** 挂载入场（KnotEnter 用）：淡入 + 相对自身高度的上滑 */
    fun mountEnter(delayMillis: Int, slideFraction: Float): EnterTransition =
        fadeIn(tween(420, delayMillis = delayMillis, easing = CubicBezierEasing(.35f, 0f, .25f, 1f))) +
            slideInVertically(
                animationSpec = tween(480, delayMillis = delayMillis, easing = Easing),
                initialOffsetY = { (it * slideFraction).toInt() },
            )
}
