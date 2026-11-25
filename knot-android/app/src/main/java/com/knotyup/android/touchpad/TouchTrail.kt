package com.knotyup.android.touchpad

import kotlin.random.Random

/**
 * 触摸轨迹引擎（纯 Kotlin，无 Compose 依赖）。
 *
 * - 手势采集层每帧 [feed] 触点坐标（按 pointerId 分轨迹）；
 * - 渲染层 60fps 调 [update] 让旧点淡出/过期，再经 [forEachStroke] 画线；
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
    }

    private val strokes = HashMap<Long, Stroke>()
    private val random = Random.Default

    /** 每帧写入触点。changes: (pointerId, x, y)；palette/random 决定新轨迹颜色。 */
    @Synchronized
    fun feed(changes: List<Triple<Long, Float, Float>>, palette: IntArray, randomColor: Boolean) {
        val ids = HashSet<Long>()
        for ((id, x, y) in changes) {
            ids.add(id)
            val s = strokes.getOrPut(id) {
                val stroke = Stroke()
                stroke.argb = if (randomColor) palette[random.nextInt(palette.size)] else palette[0]
                stroke
            }
            // 位移太小的点不追加，避免原地堆积
            val last = s.points.lastOrNull()
            if (last == null || kotlin.math.hypot(x - last.x, y - last.y) >= 1.5f) {
                s.points.add(Point(x, y, 0f))
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
    fun clear() = strokes.clear()

    /** 推进淡出；TTL 到期或轨迹无点则移除。 */
    @Synchronized
    fun update(dtSec: Float) {
        val dt = dtSec.coerceAtMost(0.05f)
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
    }

    /** 遍历每条轨迹供 Canvas 画线：points 为 (x, y, alpha)，argb 为该轨迹颜色。 */
    @Synchronized
    fun forEachStroke(action: (points: List<TrailPoint>, argb: Int) -> Unit) {
        for (s in strokes.values) {
            if (s.points.size < 2) continue
            val snapshot = s.points.map {
                TrailPoint(it.x, it.y, (1f - it.age / TTL).coerceIn(0f, 1f))
            }
            action(snapshot, s.argb)
        }
    }

    companion object {
        private const val TTL = 0.55f // 轨迹点存活时长（秒）
    }
}
