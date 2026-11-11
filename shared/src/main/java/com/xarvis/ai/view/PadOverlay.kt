package com.xarvis.ai.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.xarvis.ai.hands.LockPad

/**
 * The other phone's pattern dots / PIN keys, drawn over its picture when Android hides that screen (it shows black):
 * Rex draws his pattern on these dots, and his finger goes to the other phone as usual. Touches pass straight
 * through this view to the remote screen.
 */
class PadOverlay(context: Context, private val area: () -> RectF?) : View(context) {
    var pad: LockPad.Pad? = null
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(235, 0, 229, 255); strokeWidth = 3 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(60, 0, 229, 255) }
    private val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(235, 255, 255, 255) }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(220, 255, 255, 255); strokeWidth = 2 * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER }

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val p = pad ?: return
        val r = area() ?: return
        val dots = p.cells.map { (x, y) -> r.left + (x * r.width()).toFloat() to r.top + (y * r.height()).toFloat() }
        // Each dot's ring: a little under half the gap to its neighbour, so the dots are easy to hit.
        val gap = if (dots.size >= 2) Math.hypot((dots[1].first - dots[0].first).toDouble(), (dots[1].second - dots[0].second).toDouble()).toFloat() else 60 * d
        val radius = (gap * 0.3f).coerceIn(10 * d, 40 * d)
        dots.forEach { (x, y) ->
            canvas.drawCircle(x, y, radius, fill)
            canvas.drawCircle(x, y, radius, ring)
            canvas.drawCircle(x, y, 4 * d, centre)
        }
        p.keys.forEach { k ->
            val rect = RectF(r.left + (k.l * r.width()).toFloat(), r.top + (k.t * r.height()).toFloat(), r.left + (k.r * r.width()).toFloat(), r.top + (k.b * r.height()).toFloat())
            canvas.drawRoundRect(rect, 8 * d, 8 * d, box)
            canvas.drawText(k.label, rect.centerX(), rect.centerY() + label.textSize / 3, label)
        }
    }
}
