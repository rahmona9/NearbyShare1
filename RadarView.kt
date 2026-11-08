package com.example.nearbyshare

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.*

/** AirDrop-style radar: pulsing rings, bouncing device bubbles, progress ring, success pop. */
class RadarView(c: Context) : View(c) {
    class Peer(val id: String, val name: String, val born: Long = System.currentTimeMillis())

    val peers = mutableListOf<Peer>()
    var pulsing = false
    var linked = false
    var progress = -1f
    var onPeerClick: ((String) -> Unit)? = null
    private var successAt = 0L
    private var phase = 0f
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val over = OvershootInterpolator(2.5f)
    private val hits = mutableListOf<Pair<Peer, PointF>>()
    private val blue = 0xFF0A84FF.toInt()
    private val green = 0xFF30D158.toInt()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    fun celebrate() { successAt = System.currentTimeMillis(); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun buzz() = performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val maxR = min(width, height) / 2f
        val now = System.currentTimeMillis()
        cv.drawColor(0xFFF2F7FF.toInt())

        if (pulsing) for (i in 0..2) {
            val t = (phase + i / 3f) % 1f
            p.style = Paint.Style.FILL
            p.color = Color.argb(((1 - t) * 80).toInt(), 10, 132, 255)
            cv.drawCircle(cx, cy, 40 * d + t * (maxR - 40 * d), p)
        }
        if (progress >= 0f) {
            val r = 52 * d
            p.style = Paint.Style.STROKE; p.strokeWidth = 6 * d; p.strokeCap = Paint.Cap.ROUND
            p.color = 0x330A84FF; cv.drawCircle(cx, cy, r, p)
            p.color = blue; cv.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, progress * 360f, false, p)
        }
        // center (me)
        val age = now - successAt
        val ok = age < 1600
        p.style = Paint.Style.FILL; p.color = if (ok) green else blue
        val pop = if (ok) 1f + 0.25f * sin(min(1f, age / 500f) * PI.toFloat()) else 1f
        cv.drawCircle(cx, cy, 40 * d * pop, p)
        p.color = Color.WHITE; p.textSize = 30 * d
        cv.drawText(if (ok) "✓" else "📱", cx, cy + 11 * d, p)

        // peers
        hits.clear()
        val n = peers.size
        peers.forEachIndexed { i, pr ->
            val ang = -PI / 2 + i * 2 * PI / max(n, 1) + 0.12 * sin(phase * 2 * PI + i)
            val r = maxR * 0.72f
            val x = cx + (r * cos(ang)).toFloat(); val y = cy + (r * sin(ang)).toFloat()
            val s = over.getInterpolation(min(1f, (now - pr.born) / 600f))
            p.style = Paint.Style.FILL; p.color = if (linked) green else Color.WHITE
            p.setShadowLayer(8 * d, 0f, 2 * d, 0x33000000)
            cv.drawCircle(x, y, 32 * d * s, p); p.clearShadowLayer()
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = if (linked) green else blue
            cv.drawCircle(x, y, 32 * d * s, p)
            p.style = Paint.Style.FILL; p.color = if (linked) Color.WHITE else blue; p.textSize = 22 * d * s
            cv.drawText(pr.name.take(1).uppercase(), x, y + 8 * d * s, p)
            p.color = 0xFF1D1D1B.toInt(); p.textSize = 13 * d
            cv.drawText(pr.name.take(14), x, y + 52 * d, p)
            hits.add(pr to PointF(x, y))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && !linked)
            hits.firstOrNull { hypot(e.x - it.second.x, e.y - it.second.y) < 48 * d }?.let {
                buzz(); onPeerClick?.invoke(it.first.id)
            }
        return true
    }
}
