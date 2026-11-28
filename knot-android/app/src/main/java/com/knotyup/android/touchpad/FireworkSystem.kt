package com.knotyup.android.touchpad

import android.graphics.Color
import kotlin.random.Random

/**
 * 烟花粒子引擎（纯 Kotlin，无 Compose 依赖——渲染与采集解耦）。
 *
 * 效果：从触摸板底部发射火箭 → 升空到触点绽放 → 爆炸粒子带重力下沉、沿途留下烟雾。
 * - 火箭：起点底部(x 带随机偏移)、匀速升至目标 y，飞行中每帧喷灰色烟雾；
 * - 爆炸：到达目标后圆周散开的明亮彩色粒子（带淡淡光晕、周期随机），受重力下垂；
 * - 烟雾：火箭尾迹 + 爆炸粒子尾迹，继承粒子色（低饱和提亮的带色烟）、受重力下垂、缓慢膨胀淡出；
 * - 对象池复用；帧率由渲染层（FireworkCanvas）跟随设备刷新率驱动 update(dt)。
 */
class FireworkSystem {
    /** 渲染输出：调用方按 kind 绘制。 */
    data class RenderItem(
        val kind: Kind,
        val x: Float, val y: Float,
        val radius: Float, val alpha: Float,
        val argb: Int,
        // 历史轨迹点（与烟雾同一路径），拖尾沿此绘制
        val trailX: FloatArray? = null,
        val trailY: FloatArray? = null,
        val trailCount: Int = 0,
        val trailHead: Int = 0,
    )
    enum class Kind { ROCKET, SPARK, SMOKE }

    private class Rocket {
        var alive = false
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var targetY = 0f
        var argb = 0
        var smokeAcc = 0f
        var launchSpeed = 0f
        var palette: IntArray = intArrayOf()
        var randomColor = false
    }
    private class Spark {
        var alive = false
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var argb = 0
        var age = 0f; var life = 0f
        var smokeAcc = 0f
        // 历史位置环形缓冲：拖尾与烟雾共用同一路径
        val trailX = FloatArray(TRAIL_LEN)
        val trailY = FloatArray(TRAIL_LEN)
        var trailHead = 0
        var trailCount = 0
    }
    private class Smoke {
        var alive = false
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var radius = 0f
        var age = 0f; var life = 0f
        var argb = 0
    }

    private val rockets = Array(ROCKET_CAP) { Rocket() }
    private val sparks = Array(SPARK_CAP) { Spark() }
    private val smokes = Array(SMOKE_CAP) { Smoke() }
    private var sparkCursor = 0
    private var smokeCursor = 0
    private val random = Random.Default

    /**
     * 从底部发射一枚烟花，目标是 (x, y)。
     * speedPx 为设置的扩散速度（决定火箭升空与爆炸初速）。
     */
    fun launch(x: Float, y: Float, speedPx: Float, palette: IntArray, randomColor: Boolean) {
        val r = rockets.firstOrNull { !it.alive } ?: return
        r.alive = true
        r.x = x + (random.nextFloat() - 0.5f) * 60f
        r.y = launchBottomY
        r.targetY = y
        val dist = (r.y - y).coerceAtLeast(1f)
        val speedFactor = (speedPx / 180f).coerceIn(0.4f, 2.5f) // 设置速度 180 为基准
        val flight = (0.45f + (dist / 900f).coerceIn(0f, 0.5f)) / speedFactor // 速度越快升空越快
        r.launchSpeed = speedPx
        // 初速快（1.7 倍基础速度），上升过程中逐渐衰减到基础速度（见 update 的收敛）
        val baseVy = -dist / flight
        r.vy = baseVy * 1.7f
        r.vx = (x - r.x) / flight * 1.7f
        r.argb = if (randomColor) palette[random.nextInt(palette.size)] else palette[0]
        r.palette = palette
        r.randomColor = randomColor
        r.smokeAcc = 0f
    }

    /** 发射点 y（屏幕底部），由外部每次触碰前设置。 */
    var launchBottomY: Float = 0f

    /** 内部：火箭到达目标后绽放。 */
    private fun burst(r: Rocket) {
        val n = BURST_COUNT
        val speedScale = (r.launchSpeed / 180f).coerceIn(0.4f, 2.5f)
        for (i in 0 until n) {
            val s = sparks[sparkCursor]
            sparkCursor = (sparkCursor + 1) % SPARK_CAP
            val angle = (i.toFloat() / n) * 6.28318f + random.nextFloat() * 0.05f
            val speed = (BURST_SPEED_BASE + random.nextFloat() * BURST_SPEED_VAR) * speedScale
            s.alive = true
            s.x = r.x; s.y = r.y
            s.vx = kotlin.math.cos(angle) * speed
            s.vy = kotlin.math.sin(angle) * speed
            // 绽放粒子用调色盘颜色随机取（多样化）：random 模式整盘随机，固定色模式取首色
            val picked = if (r.randomColor) r.palette[random.nextInt(r.palette.size)] else r.palette[0]
            s.argb = brighten(picked)
            s.age = 0f
            // 周期在合理范围内拉开差距：0.7~2.2s，避免一口气全消失
            s.life = 0.7f + random.nextFloat() * 1.5f
            s.smokeAcc = 0f
            s.trailHead = 0; s.trailCount = 0
        }
    }

    fun update(dtSec: Float) {
        val dt = dtSec.coerceAtMost(0.05f)
        // 火箭
        for (r in rockets) {
            if (!r.alive) continue
            // 越高速度越衰减：按剩余距离算基础速度，向它收敛（0.985/帧）
            val remain = (r.y - r.targetY).coerceAtLeast(0f)
            val baseVy = -(remain / 0.6f).coerceAtLeast(40f)
            if (r.vy < baseVy) r.vy = (r.vy + (baseVy - r.vy) * 0.06f * (dt * 60f).coerceAtMost(2f))
            r.x += r.vx * dt
            r.vy *= 0.995f // 轻微持续衰减
            r.y += r.vy * dt
            r.smokeAcc += dt
            if (r.smokeAcc >= SMOKE_INTERVAL) {
                r.smokeAcc -= SMOKE_INTERVAL
                emitSmoke(r.x, r.y, 0f, 12f, 0.35f, smokeColor(r.argb))
            }
            if (r.y <= r.targetY) {
                burst(r)
                r.alive = false
            }
        }
        // 爆炸粒子：受重力下垂 + 轻微阻尼，沿途留带色烟雾
        for (s in sparks) {
            if (!s.alive) continue
            s.vy += SPARK_GRAVITY * dt
            s.vx *= 0.99f; s.vy *= 0.99f
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.age += dt
            s.smokeAcc += dt
            if (s.smokeAcc >= SMOKE_INTERVAL) {
                s.smokeAcc -= SMOKE_INTERVAL
                // 记录历史位置：拖尾沿此绘制，与烟雾同一路径
                s.trailX[s.trailHead] = s.x
                s.trailY[s.trailHead] = s.y
                s.trailHead = (s.trailHead + 1) % TRAIL_LEN
                if (s.trailCount < TRAIL_LEN) s.trailCount++
                emitSmoke(s.x, s.y, -s.vx * 0.05f, -s.vy * 0.05f, 0.28f, smokeColor(s.argb))
            }
            if (s.age >= s.life) s.alive = false
        }
        // 烟雾：受重力下垂 + 缓慢膨胀淡出
        for (m in smokes) {
            if (!m.alive) continue
            m.vy += SMOKE_GRAVITY * dt
            m.x += m.vx * dt
            m.y += m.vy * dt
            m.radius += SMOKE_GROW * dt
            m.age += dt
            if (m.age >= m.life) m.alive = false
        }
    }

    private fun emitSmoke(x: Float, y: Float, vx: Float, vy: Float, radius: Float, argb: Int) {
        val m = smokes[smokeCursor]
        smokeCursor = (smokeCursor + 1) % SMOKE_CAP
        m.alive = true
        m.x = x; m.y = y
        m.vx = vx; m.vy = vy
        m.radius = radius
        m.argb = argb
        m.age = 0f
        m.life = 0.5f + random.nextFloat() * 0.3f
    }

    fun forEachRenderable(action: (RenderItem) -> Unit) {
        for (r in rockets) if (r.alive) action(RenderItem(Kind.ROCKET, r.x, r.y, 3f, 1f, r.argb))
        for (s in sparks) if (s.alive) {
            val t = (s.age / s.life).coerceIn(0f, 1f)
            val fade = (1f - t * t).coerceIn(0f, 1f) // 后期渐隐更自然
            action(RenderItem(Kind.SPARK, s.x, s.y, SPARK_RADIUS, fade, s.argb, s.trailX, s.trailY, s.trailCount, s.trailHead))
        }
        for (m in smokes) if (m.alive) {
            val fade = (1f - m.age / m.life).coerceIn(0f, 1f) * 0.5f
            action(RenderItem(Kind.SMOKE, m.x, m.y, m.radius, fade, m.argb))
        }
    }

    /** 提亮颜色：保持高饱和（不泛白）+ 亮度拉满，靠 HSV 的 V 提亮而非混白。 */
    private fun brighten(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        // 饱和度不低于 0.75（原本偏灰的色也拉回鲜艳），亮度 V=1
        hsv[1] = hsv[1].coerceAtLeast(0.75f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    /** 带色烟雾：继承粒子色相但低饱和提亮（非纯白），半透明。 */
    private fun smokeColor(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[1] = (hsv[1] * 0.22f).coerceIn(0f, 1f)
        hsv[2] = 1f
        return Color.HSVToColor(0x55, hsv)
    }

    companion object {
        const val ROCKET_CAP = 8
        const val SPARK_CAP = 600
        const val SMOKE_CAP = 800
        const val BURST_COUNT = 60
        const val BURST_SPEED_BASE = 180f
        const val BURST_SPEED_VAR = 220f
        const val SPARK_GRAVITY = 220f      // 爆炸粒子下垂加速度
        const val SMOKE_GRAVITY = 130f      // 烟雾下垂（更缓）
        const val SPARK_RADIUS = 3.4f       // 爆炸粒子半径
        const val TRAIL_LEN = 18            // 拖尾历史点数（与烟雾同步）
        const val SMOKE_INTERVAL = 0.02f
        const val SMOKE_GROW = 18f
    }
}
