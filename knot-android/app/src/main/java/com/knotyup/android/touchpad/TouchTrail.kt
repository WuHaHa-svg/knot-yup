package com.knotyup.android.touchpad

import kotlin.random.Random

/**
 * 触摸轨迹引擎（纯 Kotlin，无 Compose 依赖）。
 *
 * - 手势采集层每帧 [feed] 触点坐标（按 pointerId 分轨迹）；
 * - 渲染层跟随设备刷新率调 [update] 让旧点淡出/过期，再经 [forEachStroke] 画线；
 * - 颜色由设置决定：固定色或随机（随机时每条新轨迹取一色，全程一致）；
 * - 不再区分单指/双指；粗细由渲染层的 width 参数控制；
 * - 与手势识别完全解耦：纯视觉，不影响采集与控制。
 *
 * 线程：feed 在手势协程、update/draw 在渲染协程；@Synchronized 保证结构一致，
 * 视觉丢一帧无妨。
 */
/** 供渲染层读取的轨迹点：坐标 + 透明度。 */
data class TrailPoint(val x: Float, val y: Float, val alpha: Float)

class TouchTrail {
    private class Point(var x: Float, var y: Float, var age: Float)

    private class Stroke {
        val points = ArrayList<Point>()
        var argb = 0 // 该轨迹颜色（新建时按设置取一次）
        var sparkTick = 0 // 距上次溅射的采点计数
    }

    /** 独立溅射粒子：溅出后自己运动/消失，与轨迹点生命周期无关。 */
    private class Spark {
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var age = 0f
        var life = 0f
        var radius = 0f
        var argb = 0
    }

    private val strokes = HashMap<Long, Stroke>()
    private val sparks = ArrayList<Spark>()
    private val random = Random.Default
    private val SPARK_LIFE = 0.42f
    private var rainbowMode = false  // 当前是否为随机色(跑马灯)模式
    private var rainbowPhase = 0f    // 跑马灯相位，随 update 推进

    /** 每帧写入触点。changes: (pointerId, x, y)；palette/random 决定新轨迹颜色；width 为轨迹粗细（决定溅射距离）。 */
    @Synchronized
    fun feed(changes: List<Triple<Long, Float, Float>>, palette: IntArray, randomColor: Boolean, width: Float) {
        rainbowMode = randomColor
        val ids = HashSet<Long>()
        for ((id, x, y) in changes) {
            ids.add(id)
            val s = strokes.getOrPut(id) {
                val stroke = Stroke()
                stroke.argb = if (randomColor) palette[random.nextInt(palette.size)] else palette[0] // rainbow 时渲染期覆盖
                stroke
            }
            // 位移太小的点不追加，避免原地堆积
            val last = s.points.lastOrNull()
            if (last == null || kotlin.math.hypot(x - last.x, y - last.y) >= 0.25f) {
                s.points.add(Point(x, y, 0f))
                // 每隔 2 个采点溅一次，每次 4~6 颗
                if (++s.sparkTick >= 2) {
                    s.sparkTick = 0
                    emitSparks(x, y, s.argb, width)
                }
            } else {
                last.x = x; last.y = y; last.age = 0f
            }
        }
        // 已抬起的指针：删掉其轨迹
        val it = strokes.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in ids) it.remove()
        }
    }

    /** 清空所有轨迹。 */
    @Synchronized
    fun clear() { strokes.clear(); sparks.clear() }

    /** 推进淡出；TTL 到期或轨迹无点则移除。 */
    @Synchronized
    fun update(dtSec: Float) {
        val dt = dtSec.coerceAtMost(0.05f)
        if (rainbowMode) rainbowPhase = (rainbowPhase + dt / 2.4f) % 1f
        val it = strokes.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            val pIt = s.points.iterator()
            while (pIt.hasNext()) {
                val p = pIt.next()
                p.age += dt
                if (p.age > TTL) pIt.remove()
            }
            if (s.points.isEmpty()) it.remove()
        }
        // 溅射粒子独立推进：运动 + 衰减，到期移除
        val sIt = sparks.iterator()
        while (sIt.hasNext()) {
            val sp = sIt.next()
            sp.age += dt
            if (sp.age >= sp.life) { sIt.remove(); continue }
            sp.x += sp.vx * dt
            sp.y += sp.vy * dt
            sp.vx *= 0.92f // 阻尼，溅出不远
            sp.vy *= 0.92f
        }
    }

    /** 在 (x,y) 溅出 4~6 颗细小粒子；溅射距离随轨迹粗细(width)变化，每颗再随机。 */
    private fun emitSparks(x: Float, y: Float, argb: Int, width: Float) {
        val count = 4 + random.nextInt(3) // 4~6
        // 基础溅射距离：轨迹越粗溅越远（1.2~2.2 倍线宽），保证"不太近"
        val baseDist = width * (1.2f + random.nextFloat() * 1f)
        repeat(count) {
            val sp = Spark()
            val angle = random.nextFloat() * 6.28318f
            val dist = baseDist * (0.7f + random.nextFloat() * 0.8f) // 每颗粒子距离在此范围随机
            sp.x = x + kotlin.math.cos(angle) * dist // 初始就溅出到该距离
            sp.y = y + kotlin.math.sin(angle) * dist
            val speed = 8f + random.nextFloat() * 22f // 溅出后小幅继续飞
            sp.vx = kotlin.math.cos(angle) * speed
            sp.vy = kotlin.math.sin(angle) * speed
            sp.life = SPARK_LIFE * (0.6f + random.nextFloat() * 0.4f)
            sp.radius = 0.8f + random.nextFloat() * 0.9f // 细小
            sp.argb = argb
            sparks.add(sp)
        }
    }

    /** 遍历溅射粒子供 Canvas 绘制：x,y,radius,alpha,argb。 */
    @Synchronized
    fun forEachSpark(action: (x: Float, y: Float, radius: Float, alpha: Float, argb: Int) -> Unit) {
        val rainbowColor = if (rainbowMode)
            android.graphics.Color.HSVToColor(floatArrayOf(rainbowPhase * 360f, 0.85f, 1f)) else 0
        for (sp in sparks) {
            val a = (1f - sp.age / sp.life).coerceIn(0f, 1f)
            action(sp.x, sp.y, sp.radius, a, if (rainbowMode) rainbowColor else sp.argb)
        }
    }

    /** 遍历每条轨迹供 Canvas 画线：points 为 (x, y, alpha)，argb 为该轨迹颜色。 */
    @Synchronized
    fun forEachStroke(action: (points: List<TrailPoint>, argb: Int) -> Unit) {
        var idx = 0
        for (s in strokes.values) {
            if (s.points.size < 2) continue
            val snapshot = s.points.map {
                TrailPoint(it.x, it.y, (1f - it.age / TTL).coerceIn(0f, 1f))
            }
            val color = if (rainbowMode) {
                val frac = (rainbowPhase + idx * 0.13f) % 1f
                android.graphics.Color.HSVToColor(floatArrayOf(frac * 360f, 0.85f, 1f))
            } else s.argb
            action(snapshot, color)
            idx++
        }
    }

    companion object {
        private const val TTL = 0.55f // 轨迹点存活时长（秒）
    }
}
