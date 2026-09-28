package fr.arichard.lastlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import fr.arichard.lastlauncher.calendar.AgendaView

/**
 * The home screen's content column. A plain vertical LinearLayout, plus one rule
 * a stock one can't express: the weighted middle area (where the suggestion trio
 * lives) must never shrink below what the trio needs.
 *
 * With the keyboard up, the column loses roughly half the screen; the fixed rows
 * (clock, date, status line, agenda, ticker, music) then eat the middle area's
 * weight share entirely, and the bottom-anchored trio overflows upward into the
 * music row and the agenda box. Here the agenda is the one row that gives room
 * back: after a normal measure pass, if the middle area came out short, the agenda
 * gets a tighter height budget (never under its own minimum) and the column is
 * measured once more. Everything happens inside one measure pass — no second
 * layout frame, no flicker, no animator.
 */
class HomeColumn @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** The weighted middle area whose height must stay at or above [flexMin]. */
    var flex: View? = null

    /** Pixels [flex] needs — the trio measured unconstrained plus its margin. */
    var flexMin: () -> Int = { 0 }

    /** The row that yields height when the middle area comes out short. */
    var yielder: AgendaView? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val agenda = yielder
        if (agenda != null) {
            // Start every pass from the natural size: a budget set on an earlier
            // frame must not survive once the room comes back (keyboard closed).
            agenda.heightBudget = Int.MAX_VALUE
            // Same spec as last time would hand back the cached measurement,
            // budget and all; force the real onMeasure to run.
            agenda.forceLayout()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val middle = flex ?: return
        if (agenda == null || agenda.visibility == GONE) return
        val deficit = flexMin() - middle.measuredHeight
        if (deficit <= 0) return
        val give = minOf(deficit, agenda.measuredHeight - agenda.minHeightPx())
        if (give <= 0) return
        agenda.heightBudget = agenda.measuredHeight - give
        agenda.forceLayout()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
