package fr.arichard.lastlauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.arichard.lastlauncher.apps.AppEntry
import fr.arichard.lastlauncher.databinding.ItemWheelBinding
import kotlin.math.abs
import kotlin.math.sin

/**
 * A background-less "wheel" app drawer pinned to one screen edge.
 *
 * Apps sit in a vertical column whose horizontal offset follows a half-circle: the top
 * and bottom apps hug the edge while the middle app bulges toward the screen center,
 * the rest tracing a smooth arc — a snake that forms as the drawer slides in. Scrolls
 * vertically when there are more apps than fit, and closes with an outward drag.
 *
 * It is non-modal: only the narrow band it occupies is touchable, so the clock, command
 * bar and even the opposite-edge drawer stay usable while it is open.
 */
class WheelDrawer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** -1 pinned to the left edge, +1 to the right. */
    var side: Int = -1
        private set

    val isOpen: Boolean get() = progress > 0.5f
    val isVisibleAtAll: Boolean get() = progress > 0f

    private var progress = 0f
    private val recycler = RecyclerView(context)
    private val adapter = WheelAdapter()
    private var settleAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val arcDepth = 84f * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var iconOf: (AppEntry) -> Drawable? = { null }
    private var badgeOf: (String) -> Int = { 0 }
    private var onClick: (AppEntry, View) -> Unit = { _, _ -> }
    private var onLongClick: (AppEntry, View) -> Unit = { _, _ -> }
    private var onClosed: () -> Unit = {}

    init {
        clipChildren = false
        clipToPadding = false
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.clipChildren = false
        recycler.clipToPadding = false
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        val pad = (56f * density).toInt() // keeps the arc's ends off the very top/bottom
        recycler.setPadding(0, pad, 0, pad)
        addView(
            recycler,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyArc()
        })
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyArc() }
        visibility = GONE
    }

    fun init(
        side: Int,
        iconOf: (AppEntry) -> Drawable?,
        badgeOf: (String) -> Int,
        onClick: (AppEntry, View) -> Unit,
        onLongClick: (AppEntry, View) -> Unit,
        onClosed: () -> Unit,
    ) {
        this.side = side
        this.iconOf = iconOf
        this.badgeOf = badgeOf
        this.onClick = onClick
        this.onLongClick = onLongClick
        this.onClosed = onClosed
        setupSwipeToClose()
    }

    fun bind(apps: List<AppEntry>) {
        adapter.submit(apps)
        post { applyArc() }
    }

    /** Refreshes badges without rebuilding the list (notification change). */
    fun refreshBadges() {
        if (isVisibleAtAll) adapter.notifyDataSetChanged()
    }

    /** Band width used for the slide translation; falls back before measurement. */
    private fun band(): Float = if (width > 0) width.toFloat() else 150f * density

    /** Drives the open amount while the finger tracks the pull-in / push-out. */
    fun setProgress(p: Float) {
        settleAnimator?.cancel()
        applyProgress(p)
    }

    private fun applyProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        visibility = if (progress <= 0f) GONE else VISIBLE
        translationX = if (side < 0) -band() * (1f - progress) else band() * (1f - progress)
        applyArc()
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
            duration = 220
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (target <= 0f) onClosed()
                }
            })
            start()
        }
    }

    /**
     * The half-circle, measured over the app *list*: the first and last apps hug the
     * edge, the middle app bulges toward the screen center, the rest trace the arc. The
     * bulge grows with [progress] so the snake forms as the drawer slides in.
     */
    private fun applyArc() {
        val count = adapter.itemCount
        if (count == 0 || recycler.childCount == 0) return
        val sign = if (side < 0) 1f else -1f
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val pos = recycler.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val v = if (count == 1) 0.5f else pos.toFloat() / (count - 1)
            val bump = sin(Math.PI.toFloat() * v) // 0 at first/last app, 1 at the middle one
            child.translationX = sign * arcDepth * bump * progress
            val k = 0.6f + 0.4f * bump
            child.scaleX = k
            child.scaleY = k
            child.alpha = 0.5f + 0.5f * bump
        }
    }

    private fun setupSwipeToClose() {
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var startX = 0f
            private var startY = 0f
            private var stealing = false
            private var tracker: VelocityTracker? = null

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX; startY = e.rawY; stealing = false
                        tracker?.recycle()
                        tracker = VelocityTracker.obtain()
                        tracker?.addMovement(e)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        tracker?.addMovement(e)
                        val dx = e.rawX - startX
                        val dy = e.rawY - startY
                        val outward = if (side < 0) dx < 0 else dx > 0
                        if (!stealing && outward && abs(dx) > slop && abs(dx) > abs(dy)) {
                            stealing = true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracker?.recycle(); tracker = null
                    }
                }
                return stealing
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                tracker?.addMovement(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val outward = if (side < 0) startX - e.rawX else e.rawX - startX
                        applyProgress(1f - outward / band())
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val vx = tracker?.let {
                            it.computeCurrentVelocity(1000); it.xVelocity
                        } ?: 0f
                        tracker?.recycle(); tracker = null; stealing = false
                        settle(vx)
                    }
                }
            }
        })
    }

    private inner class WheelAdapter : RecyclerView.Adapter<WheelHolder>() {
        private var apps: List<AppEntry> = emptyList()

        fun submit(list: List<AppEntry>) {
            apps = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WheelHolder {
            val b = ItemWheelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // Anchor icons to the screen edge; the arc pushes them inward from there.
            (b.wheelIconWrap.layoutParams as LayoutParams).gravity =
                Gravity.CENTER_VERTICAL or (if (side < 0) Gravity.START else Gravity.END)
            return WheelHolder(b)
        }

        override fun onBindViewHolder(holder: WheelHolder, position: Int) {
            val entry = apps[position]
            holder.binding.wheelIcon.setImageDrawable(iconOf(entry))
            val count = badgeOf(entry.packageName)
            holder.binding.wheelBadge.visibility = if (count > 0) VISIBLE else GONE
            if (count > 0) {
                holder.binding.wheelBadge.text = if (count > 99) "99+" else count.toString()
            }
            holder.binding.root.setOnClickListener { onClick(entry, holder.binding.wheelIcon) }
            holder.binding.root.setOnLongClickListener {
                onLongClick(entry, holder.binding.wheelIcon); true
            }
        }
    }

    private inner class WheelHolder(val binding: ItemWheelBinding) :
        RecyclerView.ViewHolder(binding.root)
}
