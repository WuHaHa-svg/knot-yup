package com.knotyup.android.touchpad

import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 涟漪粒子引擎（纯 Kotlin，无 Compose 依赖——渲染与采集解耦）。
 *
 * - 固定风格：涟漪——粒子从触点沿圆周匀速向外扩散，铺满整个容器；
 * - 粒子只在【飞出容器】时回收，不按存活时长消失、也不被新粒子覆盖；
 * - 对象池按需扩容 + 空闲槽复用：永不覆盖存活粒子，也不无限分配；
 * - 60fps 由渲染层（ParticleCanvas）驱动调用 [update]；
 * - 颜色支持随机（每次从色板取）或固定。
 *
 * 同一个类既用于全屏控制页（screenW/H=屏幕），也用于设置弹窗的预览容器
 * （screenW/H=预览容器尺寸）——emit 传入边界即可。
 */
class ParticleSystem {
    private class Particle {
        var alive = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var radius = 4f
        var alpha = 1f
        var argb = 0
    }

    // 可扩容池 + 空闲槽栈：新粒子优先复用已回收槽位，否则扩容，绝不覆盖存活粒子
    private var pool = Array(INITIAL_CAPACITY) { Particle() }
    private var used = 0
    private val freeSlots = ArrayDeque<Int>()
    private val random = Random.Default
    private var boundW = 0f
    private var boundH = 0f

    private fun acquire(): Particle {
        val idx = if (freeSlots.isNotEmpty()) {
            freeSlots.removeLast()
        } else {
            if (used == pool.size) {
                val bigger = Array(pool.size * 2) { Particle() }
                System.arraycopy(pool, 0, bigger, 0, pool.size)
                pool = bigger
            }
            used++
            used - 1
        }
        return pool[idx]
    }

    /**
     * 发射一簇涟漪粒子。x/y 为触点像素坐标，sizePx 已换算为像素。
     * speedPx 扩散速度，screenW/H 容器像素尺寸（据此算铺满全屏的最远距离）。
     */
    fun emit(
        x: Float,
        y: Float,
        sizePx: Float,
        count: Int,
        palette: IntArray,
        randomColor: Boolean,
        speedPx: Float,
        screenW: Float,
        screenH: Float,
    ) {
        val n = count.coerceIn(1, MAX_PER_EMIT)
        boundW = screenW
        boundH = screenH
        val maxDist = maxOf(
            hypot(x, y),
            hypot(screenW - x, y),
            hypot(x, screenH - y),
            hypot(screenW - x, screenH - y),
        )
        repeat(n) {
            val p = acquire()
            p.alive = true
            p.argb = if (randomColor) palette[random.nextInt(palette.size)] else palette[0]
            p.alpha = 1f
            p.x = x
            p.y = y
            p.radius = sizePx * (0.6f + random.nextFloat() * 0.8f)
            // 圆周均匀方向，速度设置作为基础速度
            val angle = (it.toFloat() / n) * 6.28318f + random.nextFloat() * 0.08f
            val speed = speedPx * (0.92f + random.nextFloat() * 0.16f)
            p.vx = cos(angle) * speed
            p.vy = sin(angle) * speed
        }
    }

    /** 推进粒子；只有飞出容器的粒子被回收。 */
    fun update(dtSec: Float) {
        val dt = dtSec.coerceAtMost(0.05f)
        val m = 8f
        for (i in 0 until used) {
            val p = pool[i]
            if (!p.alive) continue
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.x < -m || p.x > boundW + m || p.y < -m || p.y > boundH + m) {
                p.alive = false
                freeSlots.add(i)
            }
        }
    }

    /** 遍历存活粒子（供 Canvas 绘制）。vx/vy 供拖尾沿运动反方向绘制。 */
    fun forEachAlive(
        action: (x: Float, y: Float, vx: Float, vy: Float, radius: Float, alpha: Float, argb: Int) -> Unit
    ) {
        for (i in 0 until used) {
            val p = pool[i]
            if (p.alive) action(p.x, p.y, p.vx, p.vy, p.radius, p.alpha, p.argb)
        }
    }

    companion object {
        const val INITIAL_CAPACITY = 2048 // 初始池，按需翻倍扩容
        const val MAX_PER_EMIT = 225      // 单次发射上限（对应设置最大粒子数）
    }
}
