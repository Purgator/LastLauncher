package fr.arichard.lastlauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import fr.arichard.lastlauncher.apps.AppEntry
import fr.arichard.lastlauncher.databinding.ItemWheelBinding
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A background-less wheel of app icons pinned to one screen edge.
 *
 * Each app sits at an angle on a half-circle spanning the drawer's full height: the
 * first and last visible apps hug the edge at the top and bottom, the middle one
 * bulges toward the screen center. Few apps spread out to fill the same arc; many
 * apps keep a fixed spacing and the wheel *rolls* on vertical drags to reach the
 * rest. Opening and closing roll the whole wheel in from the bottom.
 *
 * Non-modal: only this narrow band is touchable, so everything else on the home
 * screen — including the opposite drawer — stays usable while it is open.
 */
class WheelDrawer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Carried by an app drag; [fromDrawer] is the source drawer index, -1 = external. */
    data class DragPayload(val componentKey: String, val fromDrawer: Int)

    /** Enlarged drag shadow so the app visibly "lifts" under the finger. */
    class IconShadow(view: View, private val scale: Float = 1.7f) :
        View.DragShadowBuilder(view) {
        override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
            val w = (view.width * scale).toInt().coerceAtLeast(1)
            val h = (view.height * scale).toInt().coerceAtLeast(1)
            size.set(w, h)
            touch.set(w / 2, h / 2)
        }

        override fun onDrawShadow(canvas: android.graphics.Canvas) {
            canvas.scale(scale, scale)
            view.draw(canvas)
        }
    }

    /** -1 pinned to the left edge, +1 to the right. */
    var side: Int = -1
        private set

    /** Which configured drawer is currently bound (for reorder/move drops). */
    var boundIndex: Int = 0
        private set

    val isOpen: Boolean get() = progress > 0.5f
    val isVisibleAtAll: Boolean get() = progress > 0f

    private var progress = 0f          // 0 closed .. 1 open; drives the roll-in
    private var announcedOpen = false  // onOpened fired for the current fully-open state
    private var scrollAngle = 0f       // 0 .. maxScroll(), rolls the wheel
    private var apps: List<AppEntry> = emptyList()
    private val items = ArrayList<ItemWheelBinding>()
    private var settleAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val arcDepth = 84f * density   // middle app's bulge toward the center
    private val edgePad = 6f * density     // gap between edge apps and the border
    private val iconHalf = 28f * density   // item_wheel is 56dp square
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var iconOf: (AppEntry) -> Drawable? = { null }
    private var badgeOf: (String) -> Int = { 0 }
    private var onClick: (AppEntry, View) -> Unit = { _, _ -> }
    private var onLongClick: (AppEntry, View) -> Unit = { _, _ -> }
    private var onClosed: () -> Unit = {}
    private var onVisibleChanged: (Boolean) -> Unit = {}
    private var onOpened: () -> Unit = {}

    /** (componentKey, fromDrawer, toDrawer, position) → whether the drop was accepted. */
    var onAppDropped: (String, Int, Int, Int) -> Boolean = { _, _, _, _ -> false }

    /** The host tracks the lifted app so it can dim it and fly it back if needed. */
    var onItemDragStarted: (AppEntry, View) -> Unit = { _, _ -> }

    /** Screen-space drag position updates while the finger is over this drawer. */
    var onDragMoved: (Float, Float) -> Unit = { _, _ -> }

    /**
     * A swipe over the band that isn't the drawer's own (close drag, wheel roll):
     * inward horizontals and multi-finger swipes land here so the host can run the
     * matching gesture action — the open drawer must not eat its side's gestures.
     * (dx, dy, durationMs, maxFingers).
     */
    var onSwipe: (Float, Float, Long, Int) -> Unit = { _, _, _, _ -> }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true // receive the full touch stream for band drags
        visibility = GONE
        // Accept app drags: dropping inserts at the nearest slot on the arc.
        setOnDragListener { _, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> isVisibleAtAll
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    val loc = IntArray(2)
                    getLocationInWindow(loc)
                    onDragMoved(loc[0] + event.x, loc[1] + event.y)
                    true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    val payload = event.localState as? DragPayload
                        ?: return@setOnDragListener false
                    // Returning the verdict lets a rejected drop fly back home.
                    onAppDropped(
                        payload.componentKey, payload.fromDrawer, boundIndex, dropSlot(event.y)
                    )
                }
                else -> true
            }
        }
    }

    /** Entrance pop for the just-dropped item at [position]. */
    fun popItem(position: Int) {
        val view = items.getOrNull(position)?.root ?: return
        val scale = view.scaleX
        val alpha = view.alpha
        view.scaleX = scale * 0.3f
        view.scaleY = scale * 0.3f
        view.alpha = 0f
        view.animate().scaleX(scale).scaleY(scale).alpha(alpha)
            .setDuration(280)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .start()
    }

    /** Nearest list position for a drop at [y], derived from the arc geometry. */
    private fun dropSlot(y: Float): Int {
        val h = height
        if (h == 0 || apps.isEmpty()) return 0
        val bAxis = h / 2f - iconHalf - 8f * density
        val sine = ((y - h / 2f) / bAxis).coerceIn(-1f, 1f)
        val theta = Math.toDegrees(kotlin.math.asin(sine.toDouble())).toFloat()
        val gap = spacing()
        if (gap == 0f) return 0
        return Math.round((theta + HALF_SPAN + scrollAngle) / gap).coerceIn(0, apps.size)
    }

    fun init(
        side: Int,
        iconOf: (AppEntry) -> Drawable?,
        badgeOf: (String) -> Int,
        onClick: (AppEntry, View) -> Unit,
        onLongClick: (AppEntry, View) -> Unit,
        onClosed: () -> Unit,
        onVisibleChanged: (Boolean) -> Unit = {},
        onOpened: () -> Unit = {},
    ) {
        this.side = side
        this.iconOf = iconOf
        this.badgeOf = badgeOf
        this.onClick = onClick
        this.onLongClick = onLongClick
        this.onClosed = onClosed
        this.onVisibleChanged = onVisibleChanged
        this.onOpened = onOpened
    }

    fun bind(apps: List<AppEntry>, drawerIndex: Int = boundIndex) {
        flingAnimator?.cancel()
        this.apps = apps
        boundIndex = drawerIndex
        scrollAngle = 0f.coerceIn(minScroll(), maxScroll())
        rebuildViews()
        layoutIcons()
    }

    /** Re-binds only the badge bubbles (notification change). */
    fun refreshBadges() {
        if (!isVisibleAtAll) return
        for ((i, item) in items.withIndex()) {
            bindBadge(item, apps[i].packageName)
        }
    }

    // ------------------------------------------------------------- geometry

    /** Angular gap between neighbours; few apps stretch to fill the whole arc. */
    private fun spacing(): Float =
        if (apps.size <= 1) 0f else ARC_SPAN / (minOf(apps.size, MAX_VISIBLE) - 1)

    // The wheel rolls freely enough that ANY app — including the first and last —
    // can be brought to the middle (the max-bulge sweet spot).
    private fun minScroll(): Float = -HALF_SPAN

    private fun maxScroll(): Float =
        maxOf(-HALF_SPAN, (apps.size - 1) * spacing() - HALF_SPAN)

    /**
     * Places every icon on the arc. An icon's angle combines its slot, the current
     * roll, and the open progress (closed = everything rolled past the bottom, so
     * opening rolls the wheel in from below up to its resting place).
     */
    private fun layoutIcons() {
        val h = height
        if (h == 0 || items.isEmpty()) return
        val bAxis = h / 2f - iconHalf - 8f * density // vertical semi-axis, icons inset
        val gap = spacing()
        val roll = (1f - progress) * ROLL_IN_DEG
        for ((i, item) in items.withIndex()) {
            val view = item.root
            // -90 = top edge, 0 = middle (max bulge), +90 = bottom edge.
            val theta = -HALF_SPAN + i * gap - scrollAngle + roll
            if (theta < -FADE_LIMIT || theta > FADE_LIMIT) {
                view.visibility = INVISIBLE
                continue
            }
            view.visibility = VISIBLE
            val rad = Math.toRadians(theta.toDouble())
            val bump = cos(rad).toFloat()            // 1 middle, 0 at the arc ends
            val xCenter = edgePad + iconHalf + arcDepth * bump
            view.x = if (side < 0) xCenter - iconHalf else width - xCenter - iconHalf
            view.y = h / 2f + bAxis * sin(rad).toFloat() - iconHalf
            val scale = 0.62f + 0.38f * bump
            view.scaleX = scale
            view.scaleY = scale
            // Fade out just past the arc ends while rolling, and with closing progress.
            val edgeFade = ((FADE_LIMIT - abs(theta)) / (FADE_LIMIT - HALF_SPAN)).coerceIn(0f, 1f)
            view.alpha = (0.55f + 0.45f * bump) * edgeFade * progress.coerceIn(0f, 1f)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutIcons()
    }

    private fun rebuildViews() {
        removeAllViews()
        items.clear()
        val inflater = LayoutInflater.from(context)
        for (entry in apps) {
            val item = ItemWheelBinding.inflate(inflater, this, false)
            item.wheelIcon.setImageDrawable(iconOf(entry))
            bindBadge(item, entry.packageName)
            item.root.setOnClickListener { onClick(entry, item.wheelIcon) }
            // Long-press picks the app up for drag & drop (reorder, or move to the
            // other drawer); the app menu stays available from search and suggestions.
            item.root.setOnLongClickListener { view ->
                onLongClick(entry, item.wheelIcon)
                onItemDragStarted(entry, view)
                val data = android.content.ClipData.newPlainText("app", entry.componentKey)
                view.startDragAndDrop(
                    data, IconShadow(item.wheelIcon),
                    DragPayload(entry.componentKey, boundIndex), 0
                )
                true
            }
            addView(item.root)
            items.add(item)
        }
    }

    private fun bindBadge(item: ItemWheelBinding, pkg: String) {
        val count = badgeOf(pkg)
        item.wheelBadge.visibility = if (count > 0) VISIBLE else GONE
        if (count > 0) item.wheelBadge.text = if (count > 99) "99+" else count.toString()
    }

    // ------------------------------------------------------------- open/close

    /** Band width used for the close-drag mapping; falls back before measurement. */
    private fun band(): Float = if (width > 0) width.toFloat() else 150f * density

    fun setProgress(p: Float) {
        settleAnimator?.cancel()
        applyProgress(p)
    }

    private fun applyProgress(p: Float) {
        val wasVisible = progress > 0f
        progress = p.coerceIn(0f, 1f)
        if (progress < 1f) announcedOpen = false
        visibility = if (progress <= 0f) GONE else VISIBLE
        if (wasVisible != progress > 0f) onVisibleChanged(progress > 0f)
        layoutIcons()
    }

    /** Positive [inward] = pulled toward the screen center (opening). */
    fun dragBy(inward: Float) = setProgress(inward / band())

    fun open(animate: Boolean) = animateTo(1f, animate)

    fun close(animate: Boolean) = animateTo(0f, animate)

    /** On release, snap open or closed by drag distance, biased by a decisive flick. */
    fun settle(velocityX: Float) {
        val fast = abs(velocityX) > 800 * density
        val opening = if (side < 0) velocityX > 0 else velocityX < 0
        val open = if (fast) opening else progress > 0.4f
        animateTo(if (open) 1f else 0f, animate = true)
    }

    private fun animateTo(target: Float, animate: Boolean) {
        settleAnimator?.cancel()
        flingAnimator?.cancel()
        // onOpened must fire whenever the wheel clicks into place, including a drag
        // that already crossed the half-open mark before release — announcedOpen
        // (reset while below 1) dedupes instead of the pre-settle isOpen state.
        fun settled() {
            if (target <= 0f) onClosed()
            else if (target >= 1f && !announcedOpen) {
                announcedOpen = true
                onOpened()
            }
        }
        if (!animate) {
            applyProgress(target)
            settled()
            return
        }
        settleAnimator = ValueAnimator.ofFloat(progress, target).apply {
            duration = ROLL_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    settled()
                }
            })
            start()
        }
    }

    // ----------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downScroll = 0f
    private var maxPointers = 1
    private var mode = Mode.NONE
    private var tracker: VelocityTracker? = null
    private var flingAnimator: ValueAnimator? = null

    /** Rolls on with the release velocity, decaying — a hard flick sweeps the whole list. */
    private fun startFling(velocityDegPerSec: Float) {
        flingAnimator?.cancel()
        if (abs(velocityDegPerSec) < 40f) return
        // Total travel ≈ v·τ with τ=0.5s of exponential-ish decay, clamped to the ends.
        val target = (scrollAngle + velocityDegPerSec * 0.5f)
            .coerceIn(minScroll(), maxScroll())
        if (target == scrollAngle) return
        val duration = (250 + 2.5f * abs(target - scrollAngle)).toLong().coerceAtMost(1400)
        flingAnimator = ValueAnimator.ofFloat(scrollAngle, target).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                scrollAngle = it.animatedValue as Float
                layoutIcons()
            }
            start()
        }
    }

    private enum class Mode { NONE, SCROLL, CLOSE, FORWARD }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingAnimator?.cancel() // catch the rolling wheel
                downX = ev.rawX
                downY = ev.rawY
                downTime = ev.eventTime
                downScroll = scrollAngle
                maxPointers = 1
                mode = Mode.NONE
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN ->
                maxPointers = maxOf(maxPointers, ev.pointerCount)
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                decideMode(ev)
                if (mode != Mode.NONE) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle(); tracker = null
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN ->
                maxPointers = maxOf(maxPointers, ev.pointerCount)
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) decideMode(ev)
                when (mode) {
                    Mode.SCROLL -> {
                        // Drag up rolls the wheel forward. ~1° per arc-pixel keeps
                        // finger travel and icon travel visually matched.
                        val degPerPx = ARC_SPAN / (height.coerceAtLeast(1)).toFloat()
                        scrollAngle = (downScroll + (downY - ev.rawY) * degPerPx)
                            .coerceIn(minScroll(), maxScroll())
                        layoutIcons()
                    }
                    Mode.CLOSE -> {
                        val outward = if (side < 0) downX - ev.rawX else ev.rawX - downX
                        applyProgress(1f - outward / band())
                    }
                    Mode.FORWARD, Mode.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasTap = mode == Mode.NONE &&
                    abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop
                if (mode == Mode.CLOSE) {
                    val vx = tracker?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    settle(vx)
                } else if (mode == Mode.SCROLL && ev.actionMasked == MotionEvent.ACTION_UP) {
                    val vy = tracker?.let { it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f
                    // Drag up (negative vy) rolls the wheel forward.
                    startFling(-vy * ARC_SPAN / (height.coerceAtLeast(1)).toFloat())
                } else if (mode == Mode.FORWARD && ev.actionMasked == MotionEvent.ACTION_UP) {
                    onSwipe(
                        ev.rawX - downX, ev.rawY - downY,
                        ev.eventTime - downTime, maxPointers,
                    )
                } else if (wasTap && ev.actionMasked == MotionEvent.ACTION_UP) {
                    // Tap on the band's empty space: dismiss, matching the home tap.
                    performClick()
                    close(animate = true)
                }
                mode = Mode.NONE
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }

    private fun decideMode(ev: MotionEvent) {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        if (abs(dy) > slop && abs(dy) > abs(dx)) {
            mode = Mode.SCROLL
            downY = ev.rawY // avoid the initial slop jump
            downScroll = scrollAngle
        } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
            val outward = if (side < 0) dx < 0 else dx > 0
            // Outward is the drawer's own close drag; inward belongs to the host
            // (same-side gesture bindings must keep working over an open drawer).
            // Inward asks for double the slop: it steals the touch from an icon
            // tap, so a slightly sloppy tap must not be mistaken for a swipe.
            if (outward) mode = Mode.CLOSE
            else if (abs(dx) > slop * 2) mode = Mode.FORWARD
        }
    }

    override fun onDetachedFromWindow() {
        settleAnimator?.cancel()
        flingAnimator?.cancel()
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val ARC_SPAN = 180f     // degrees covered by the visible half-circle
        const val HALF_SPAN = 90f
        const val FADE_LIMIT = 100f   // icons fade out between 90° and here
        const val ROLL_IN_DEG = 150f  // open/close rolls the wheel in from the bottom
        const val ROLL_MS = 260L
        const val MAX_VISIBLE = 13    // fixed spacing once the arc is this full
    }
}
