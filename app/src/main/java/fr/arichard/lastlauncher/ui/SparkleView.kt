package fr.arichard.lastlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiny firework-powder sparkles that live around the finger during the suggestion
 * swipe: little glowing grains and 4-point stars tossed out near the touch point,
 * drifting, twinkling and dying within a second.
 *
 * Battery-safe by construction: particles are only born from [emit]/[burst] (i.e.
 * while a finger is actively swiping) and the frame loop re-schedules itself only
 * while at least one particle is alive — an idle screen draws nothing.
 */
class SparkleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var life: Float,          // seconds left
        val maxLife: Float,
        val size: Float,          // px
        val twinklePhase: Float,  // desynchronizes the flicker
        val star: Boolean,        // 4-point star vs powder grain
    )

    private val particles = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = java.util.Random()
    private val density = resources.displayMetrics.density
    private var color = 0xFF00E5FF.toInt()
    private var lastFrameNs = 0L

    init {
        // Never intercepts anything; it is a pure overlay.
        isClickable = false
        isFocusable = false
    }

    fun setColor(c: Int) {
        color = c
    }

    /**
     * Sprinkles a pinch of powder near ([x], [y]). Call per touch move; [intensity]
     * 0..1 scales how much comes out as the swipe approaches its trigger.
     */
    fun emit(x: Float, y: Float, intensity: Float) {
        if (particles.size >= MAX_PARTICLES) return
        val count = 1 + (intensity * 2.5f).toInt()
        repeat(count) {
            val angle = random.nextFloat() * (Math.PI * 2).toFloat()
            val speed = (30f + 130f * intensity * random.nextFloat()) * density
            spawn(
                x + gauss() * 14f * density,
                y + gauss() * 14f * density,
                cos(angle) * speed,
                // Upward drift: sparks float rather than sink.
                sin(angle) * speed - 40f * density,
                life = 0.35f + random.nextFloat() * 0.5f,
            )
        }
        wake()
    }

    /** Completion firework: a dense puff of powder flying outward from the point. */
    fun burst(x: Float, y: Float) {
        repeat(BURST_PARTICLES) {
            val angle = random.nextFloat() * (Math.PI * 2).toFloat()
            val speed = (140f + 260f * random.nextFloat()) * density
            spawn(
                x, y,
                cos(angle) * speed,
                sin(angle) * speed - 60f * density,
                life = 0.45f + random.nextFloat() * 0.55f,
            )
        }
        wake()
    }

    private fun spawn(x: Float, y: Float, vx: Float, vy: Float, life: Float) {
        if (particles.size >= MAX_PARTICLES + BURST_PARTICLES) return
        particles.add(
            Particle(
                x, y, vx, vy,
                life = life, maxLife = life,
                size = (0.8f + random.nextFloat() * 1.6f) * density,
                twinklePhase = random.nextFloat() * (Math.PI * 2).toFloat(),
                star = random.nextFloat() < 0.3f,
            )
        )
    }

    /** Rough normal distribution so grains cluster near the finger. */
    private fun gauss(): Float =
        (random.nextFloat() + random.nextFloat() + random.nextFloat()) / 1.5f - 1f

    private fun wake() {
        if (visibility != VISIBLE) visibility = VISIBLE
        if (lastFrameNs == 0L) {
            lastFrameNs = System.nanoTime()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (particles.isEmpty()) {
            lastFrameNs = 0L
            // Hide once everything died, off the draw pass.
            post { if (particles.isEmpty() && visibility == VISIBLE) visibility = GONE }
            return
        }
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNs = now

        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += GRAVITY * density * dt      // gentle fall at the end of the arc
            p.vx *= 1f - DRAG * dt
            p.vy *= 1f - DRAG * dt

            val fraction = p.life / p.maxLife
            val age = p.maxLife - p.life
            // The twinkle: each grain flickers on its own rhythm.
            val twinkle = 0.55f + 0.45f * sin(p.twinklePhase + age * 22f)
            paint.color = color
            paint.alpha = (255f * fraction * twinkle).toInt().coerceIn(0, 255)
            if (p.star) {
                val r = p.size * (1.5f + fraction)
                paint.strokeWidth = p.size * 0.5f
                canvas.drawLine(p.x - r, p.y, p.x + r, p.y, paint)
                canvas.drawLine(p.x, p.y - r, p.x, p.y + r, paint)
            } else {
                canvas.drawCircle(p.x, p.y, p.size * (0.5f + 0.5f * fraction), paint)
            }
        }
        if (particles.isNotEmpty()) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        particles.clear()
        lastFrameNs = 0L
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_PARTICLES = 70   // emission cap; bursts may briefly exceed it
        const val BURST_PARTICLES = 26
        const val GRAVITY = 190f       // dp/s²
        const val DRAG = 2.2f          // per-second velocity decay
    }
}
