package app.orchard.tv.lite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.orchard.tv.lite.Ui.textPx

/**
 * The OK key as the full app's Pressable handles it: a click fires on release only for a press that
 * began on the same control, so the release of the long press that opened a menu does not choose
 * the menu's first row. A held key (key repeats) is a long press.
 */
class Press {
    private var down = false
    private var long = false

    /** Handles [e] when it is an OK key; true when it was one. */
    fun onKey(e: KeyEvent, click: () -> Unit, longPress: (() -> Unit)? = null): Boolean {
        if (!isOk(e.keyCode)) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                down = true
                long = false
            } else if (down && !long && longPress != null) {
                long = true
                longPress()
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            val fire = down && !long
            down = false
            long = false
            if (fire) click()
        }
        return true
    }

    fun reset() {
        down = false
        long = false
    }

    companion object {
        fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
    }
}

sealed class Row(val label: String) {
    class Action(label: String, val value: String? = null, val run: () -> Unit) : Row(label)
    /** Left and right step through [options]; OK steps forward. */
    class Choice(label: String, val options: List<String>, var index: Int, val change: (Int) -> Unit) : Row(label)
    /** Text only, not focusable. */
    class Note(label: String) : Row(label)
}

/**
 * A sheet at the right edge, in the manner of tvOS settings: a title and rows. Opaque and still: no
 * blur, no dim layer behind it, no animation but the focus moving, so opening it costs one draw of
 * its own area.
 */
class Panel(context: Context, title: String, private val rows: List<Row>, private val close: () -> Unit) : FrameLayout(context) {
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val views = ArrayList<RowView>()
    private var focus = rows.indexOfFirst { it !is Row.Note }
    private val press = Press()
    private val rowH = Ui.pxi(40f)
    private val listTop = Ui.pxi(72f)
    private val listH: Int

    init {
        val w = Ui.pxi(340f)
        val margin = Ui.pxi(24f)
        layoutParams = LayoutParams(w, Ui.screenH - 2 * margin, Gravity.END or Gravity.TOP).apply {
            topMargin = margin
            rightMargin = margin
        }
        listH = Ui.screenH - 2 * margin - listTop - Ui.pxi(20f)
        @Suppress("DEPRECATION")
        setBackgroundDrawable(GradientDrawable().apply {
            setColor(Ui.PANEL)
            cornerRadius = Ui.px(20f)
        })
        val pad = Ui.pxi(16f)
        addView(TextView(context).apply {
            text = title
            setTextColor(Ui.WHITE)
            textPx(22f)
            typeface = Ui.bold
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = pad + Ui.pxi(8f)
            rightMargin = pad
            topMargin = Ui.pxi(28f)
        })
        val clip = FrameLayout(context)
        clip.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(clip, LayoutParams(LayoutParams.MATCH_PARENT, listH).apply {
            topMargin = listTop
            leftMargin = pad
            rightMargin = pad
        })
        rows.forEachIndexed { i, row ->
            val v = RowView(context, row)
            v.focused = i == focus
            views += v
            list.addView(v, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, rowH))
        }
        keepInView()
    }

    fun onKey(e: KeyEvent): Boolean {
        val row = rows.getOrNull(focus)
        if (press.onKey(e, click = {
                when (row) {
                    is Row.Action -> row.run()
                    is Row.Choice -> step(row, 1)
                    else -> {}
                }
            })) return true
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) close()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return isNav(e.keyCode)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> (row as? Row.Choice)?.let { step(it, -1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> (row as? Row.Choice)?.let { step(it, 1) }
            else -> return false
        }
        return true
    }

    private fun isNav(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun step(row: Row.Choice, by: Int) {
        row.index = (row.index + by + row.options.size) % row.options.size
        views[focus].invalidate()
        row.change(row.index)
    }

    private fun move(by: Int) {
        var i = focus
        do {
            i += by
            if (i !in rows.indices) return
        } while (rows[i] is Row.Note)
        views[focus].focused = false
        focus = i
        views[focus].focused = true
        keepInView()
    }

    /** Scrolls the list by translation (a property, no new layout) so the focused row shows. */
    private fun keepInView() {
        if (focus < 0) return
        val top = focus * rowH + list.translationY
        if (top < 0) list.translationY = -(focus * rowH).toFloat()
        else if (top + rowH > listH) list.translationY = (listH - (focus + 1) * rowH).toFloat()
    }

    private class RowView(context: Context, val row: Row) : View(context) {
        var focused = false
            set(v) {
                if (field != v) { field = v; invalidate() }
            }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.WHITE }
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.px(17f) }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val inset = Ui.px(3f)
            if (focused) {
                rect.set(0f, inset, w, h - inset)
                canvas.drawRoundRect(rect, Ui.px(10f), Ui.px(10f), bg)
            }
            val pad = Ui.px(12f)
            val base = h / 2 - (text.descent() + text.ascent()) / 2
            text.color = when {
                row is Row.Note -> Ui.FAINT
                focused -> Ui.INK
                else -> Ui.WHITE
            }
            val value = when (row) {
                is Row.Action -> row.value
                is Row.Choice -> "‹ " + row.options[row.index] + " ›"
                else -> null
            }
            val valueW = if (value != null) text.measureText(value) else 0f
            val room = w - 2 * pad - if (value != null) valueW + pad else 0f
            canvas.drawText(TextUtils.ellipsize(row.label, text, room, TextUtils.TruncateAt.END).toString(), pad, base, text)
            if (value != null) {
                text.color = if (focused) 0xFF55555C.toInt() else Ui.DIM
                canvas.drawText(value, w - pad - valueW, base, text)
            }
        }
    }
}
