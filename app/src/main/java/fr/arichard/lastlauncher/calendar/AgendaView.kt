package fr.arichard.lastlauncher.calendar

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import fr.arichard.lastlauncher.R
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The home-screen agenda stream: a few terminal-style lines of upcoming events
 * under the status line. No cards, no grid — the next event leads in the accent
 * color with a countdown, later days get a quiet `┄` separator, and tapping an
 * event unfolds its details in place (tappable location → maps).
 *
 * Scrolls vertically through the configured horizon. Everything it can't use —
 * horizontal swipes, multi-finger swipes, verticals it can't scroll — is forwarded
 * to the host via [onSwipe], so home gestures keep working over it.
 */
class AgendaView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    /** Fired with a raw location string; the host routes it to a maps app. */
    var onLocationClick: (String) -> Unit = {}

    /** Fired to open the event in the calendar app. */
    var onOpenEvent: (Agenda.EventInstance) -> Unit = {}

    /** Unhandled swipes forwarded to the host: (dx, dy, durationMs, fingers). */
    var onSwipe: (Float, Float, Long, Int) -> Unit = { _, _, _, _ -> }

    /** Host's haptic hook, so feedback stays gated by the single prefs check. */
    var haptic: (View) -> Unit = {}

    /** When true, tapping a row opens the calendar app instead of unfolding. */
    var tapOpensApp = false

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var accent = 0xFF00E5FF.toInt()
    private var rows: List<Agenda.Row> = emptyList()
    private var expandedKey: Pair<Long, Long>? = null
    private val density = resources.displayMetrics.density
    private val timeFormat = android.text.format.DateFormat.getTimeFormat(context)

    init {
        // The stream is a centered, width-capped block — full-bleed lines read as a
        // wall of text and collide visually with the wheel drawers at the edges.
        addView(
            list,
            LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            )
        )
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setAccent(color: Int) {
        accent = color
    }

    /** Renders the stream; keeps the fold state and scroll position across refreshes. */
    fun submit(rows: List<Agenda.Row>) {
        this.rows = rows
        // Drop the fold state if its event left the window.
        if (expandedKey != null && rows.none { it is Agenda.Row.Event && key(it.event) == expandedKey }) {
            expandedKey = null
        }
        render()
    }

    private fun key(e: Agenda.EventInstance) = e.eventId to e.begin

    private fun render() {
        val scroll = scrollY
        list.removeAllViews()
        val now = System.currentTimeMillis()
        for (row in rows) {
            when (row) {
                is Agenda.Row.DayHeader -> list.addView(dayHeaderView(row))
                is Agenda.Row.Event ->
                    if (key(row.event) == expandedKey) list.addView(expandedView(row))
                    else list.addView(collapsedView(row, now))
            }
        }
        post { scrollTo(0, scroll) }
    }

    private fun line(sizeSp: Float = 12f): TextView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.02f
        textSize = sizeSp
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.START
        maxWidth = (300 * density).toInt()
        setPadding(0, (1 * density).toInt(), 0, (1 * density).toInt())
    }

    private fun dayHeaderView(row: Agenda.Row.DayHeader): TextView = line(11f).apply {
        text = when (row.kind) {
            Agenda.DayKind.TOMORROW -> "┄ ${context.getString(R.string.agenda_tomorrow)}"
            Agenda.DayKind.LATER -> "┄ " + java.text.SimpleDateFormat("EEE d", Locale.getDefault())
                .format(Date(row.dayStart))
        }
        setTextColor(ColorUtils.setAlphaComponent(context.getColor(R.color.text_secondary), 0x99))
        setPadding(0, (8 * density).toInt(), 0, (2 * density).toInt())
    }

    private fun collapsedView(row: Agenda.Row.Event, now: Long): TextView = line().apply {
        val e = row.event
        val time = if (e.allDay) context.getString(R.string.agenda_all_day)
        else timeFormat.format(Date(e.begin))
        val suffix = when {
            !row.next || e.allDay -> ""
            row.ongoing -> " · " + context.getString(R.string.agenda_now)
            else -> " · " + countdown(Agenda.minutesUntil(e.begin, now))
        }
        text = "${if (row.next) "▸" else " "} $time ${e.title}$suffix"
        setTextColor(
            if (row.next) accent
            else ColorUtils.setAlphaComponent(context.getColor(R.color.text_secondary), 0xD0)
        )
        setOnClickListener { view ->
            haptic(view)
            if (tapOpensApp) {
                onOpenEvent(e)
            } else {
                expandedKey = key(e)
                render()
            }
        }
        setOnLongClickListener { view ->
            haptic(view)
            onOpenEvent(e)
            true
        }
    }

    /** The unfolded event: full time range, tappable location, open-in-calendar. */
    private fun expandedView(row: Agenda.Row.Event): LinearLayout {
        val e = row.event
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), 0, 0, (2 * density).toInt())
        }
        // A thin accent tick marks the open block, drawn by a colored stripe view.
        val stripe = View(context).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(accent, 0xB0))
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stripe, LinearLayout.LayoutParams((2 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(block, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val range = if (e.allDay) context.getString(R.string.agenda_all_day)
        else "${timeFormat.format(Date(e.begin))}–${timeFormat.format(Date(e.end))}"
        block.addView(line().apply {
            text = "$range ${e.title}"
            setTextColor(context.getColor(R.color.text_primary))
            setOnClickListener { view ->
                haptic(view)
                expandedKey = null
                render()
            }
        })
        if (e.location.isNotEmpty()) {
            block.addView(line().apply {
                text = "⌖ ${e.location} ↗"
                setTextColor(accent)
                setOnClickListener { view ->
                    haptic(view)
                    onLocationClick(e.location)
                }
            })
        }
        block.addView(line(12f).apply {
            text = "▸ " + context.getString(R.string.agenda_open_event)
            setTextColor(ColorUtils.setAlphaComponent(context.getColor(R.color.text_secondary), 0xB0))
            setOnClickListener { view ->
                haptic(view)
                onOpenEvent(e)
            }
        })
        return wrap
    }

    private fun countdown(minutes: Int): String = when {
        minutes < 1 -> context.getString(R.string.agenda_now)
        minutes < 60 -> context.getString(R.string.agenda_in_min, minutes)
        else -> context.getString(R.string.agenda_in_hours, minutes / 60, minutes % 60)
    }

    // The stream never grows past a fifth of the screen — the middle area (hints,
    // suggestions, swipe band) must keep its room; deeper days scroll instead.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * 0.20f).toInt()
        val capped = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(
                minOf(cap, MeasureSpec.getSize(heightMeasureSpec)), MeasureSpec.AT_MOST
            )
        }
        super.onMeasure(widthMeasureSpec, capped)
    }

    // ----------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 1
    private var forwarding = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** True once the gesture is one the host should get, not this scroller. */
    private fun shouldForward(ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        // Claim for the host: multi-finger swipes, horizontal swipes, and
        // verticals this view has no room to scroll toward.
        val horizontal = abs(dx) > slop * 2 && abs(dx) > abs(dy)
        val deadVertical = abs(dy) > slop && abs(dy) > abs(dx) &&
            !canScrollVertically(if (dy < 0) 1 else -1)
        return maxPointers > 1 || horizontal || deadVertical
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downTime = ev.eventTime
                maxPointers = 1
                forwarding = false
            }
            MotionEvent.ACTION_POINTER_DOWN ->
                maxPointers = maxOf(maxPointers, ev.pointerCount)
            MotionEvent.ACTION_MOVE ->
                if (!forwarding && shouldForward(ev)) {
                    forwarding = true
                    return true
                }
        }
        return if (forwarding) true else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            maxPointers = maxOf(maxPointers, ev.pointerCount)
        }
        // Touches on non-clickable children (day headers, padding) reach this
        // method without going through intercept — decide here too.
        if (!forwarding && ev.actionMasked == MotionEvent.ACTION_MOVE && shouldForward(ev)) {
            forwarding = true
            // Let the scroller wind down its own drag state cleanly.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.onTouchEvent(cancel)
            cancel.recycle()
        }
        if (forwarding) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    onSwipe(
                        ev.rawX - downX, ev.rawY - downY,
                        ev.eventTime - downTime, maxPointers,
                    )
                    forwarding = false
                }
                MotionEvent.ACTION_CANCEL -> forwarding = false
            }
            return true
        }
        return super.onTouchEvent(ev)
    }
}
