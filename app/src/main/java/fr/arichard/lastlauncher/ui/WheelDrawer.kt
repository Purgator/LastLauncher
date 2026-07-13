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

    /** -1 pinned to the left edge, +1 to the right. */
    var side: Int = -1
        private set

    val isOpen: Boolean get() = progress > 0.5f
    val isVisibleAtAll: Boolean get() = progress > 0f

    private var progress = 0f          // 0 closed .. 1 open; drives the roll-in
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

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true // receive the full touch stream for band drags
        visibility = GONE
    }

    fun init(
        side: Int,
        iconOf: (AppEntry) -> Drawable?,
        badgeOf: (String) -> Int,
        onClick: (AppEntry, View) -> Unit,
        onLongClick: (AppEntry, View) -> Unit,
        onClosed: () -> Unit,
        onVisibleChanged: (Boolean) -> Unit = {},
    ) {
        this.side = side
        this.iconOf = iconOf
        this.badgeOf = badgeOf
        this.onClick = onClick
        this.onLongClick = onLongClick
        this.onClosed = onClosed
        this.onVisibleChanged = onVisibleChanged
    }

    fun bind(apps: List<AppEntry>) {
        this.apps = apps
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
            item.root.setOnLongClickListener { onLongClick(entry, item.wheelIcon); true }
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
        if (!animate) {
            applyProgress(target)
            if (target <= 0f) onClosed()
            return
        }
        settleAnimator = ValueAnimator.ofFloat(progress, target).apply {
            duration = ROLL_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (target <= 0f) onClosed()
                }
            })
            start()
        }
    }

    // ----------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var mode = Mode.NONE
    private var tracker: VelocityTracker? = null

    private enum class Mode { NONE, SCROLL, CLOSE }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downScroll = scrollAngle
                mode = Mode.NONE
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
            }
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
                    Mode.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasTap = mode == Mode.NONE &&
                    abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop
                if (mode == Mode.CLOSE) {
                    val vx = tracker?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    settle(vx)
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
        } else {
            val outward = if (side < 0) dx < 0 else dx > 0
            if (outward && abs(dx) > slop && abs(dx) > abs(dy)) mode = Mode.CLOSE
        }
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
