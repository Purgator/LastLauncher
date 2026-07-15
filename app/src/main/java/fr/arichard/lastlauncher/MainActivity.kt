package fr.arichard.lastlauncher

import android.Manifest
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.arichard.lastlauncher.apps.AppEntry
import fr.arichard.lastlauncher.apps.AppRepository
import fr.arichard.lastlauncher.calendar.Agenda
import fr.arichard.lastlauncher.calendar.CalendarFeed
import fr.arichard.lastlauncher.command.CommandProcessor
import fr.arichard.lastlauncher.command.SearchMode
import fr.arichard.lastlauncher.databinding.ActivityMainBinding
import fr.arichard.lastlauncher.gesture.GestureAction
import fr.arichard.lastlauncher.gesture.GestureBinding
import fr.arichard.lastlauncher.lock.LockService
import fr.arichard.lastlauncher.notify.NotifListener
import fr.arichard.lastlauncher.predict.PredictionEngine
import fr.arichard.lastlauncher.settings.InsightsActivity
import fr.arichard.lastlauncher.settings.Prefs
import fr.arichard.lastlauncher.settings.SettingsActivity
import fr.arichard.lastlauncher.ui.AppAdapter
import fr.arichard.lastlauncher.ui.AppPickerDialog
import fr.arichard.lastlauncher.ui.WheelDrawer
import fr.arichard.lastlauncher.ui.StatusLine
import fr.arichard.lastlauncher.update.UpdateManager
import fr.arichard.lastlauncher.weather.WeatherProvider
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The home screen. One page, three predicted apps, a type-to-launch command bar and
 * a handful of gestures — nothing else to manage.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter

    private var allAppsOpen = false
    private var searchMode = SearchMode.SMART
    private var pendingUpdateVersion: String? = null
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "main-io") }
    private val repoListener: () -> Unit = { onAppsChanged() }
    private val notifListener: () -> Unit = { onNotificationsChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        repo = (application as LauncherApp).repo

        setupInsets()
        setupGestures()
        setupSearch()
        setupSuggestionClicks()
        setupDrawer()
        binding.updatePill.setOnClickListener {
            haptic(binding.updatePill)
            pendingUpdateVersion?.let { v -> UpdateManager.install(this, v) }
        }
        binding.clock.setOnClickListener { launchClockTarget() }
        binding.weather.setOnClickListener { launchWeatherTarget() }
        binding.starterPill.setOnClickListener {
            haptic(binding.starterPill)
            showStarterPicker()
        }
        setupContextualLongPress()
        setupDragWatcher()
        setupAgenda()
        repo.addListener(repoListener)
        NotifListener.addListener(notifListener)
    }

    /** Long-pressing a home-screen element jumps straight to its settings domain. */
    private fun setupContextualLongPress() {
        fun View.opensSettings(screen: String) {
            setOnLongClickListener {
                haptic(this)
                SettingsActivity.open(this@MainActivity, screen)
                true
            }
        }
        binding.clock.opensSettings(SettingsActivity.SCREEN_CLOCK_WEATHER)
        binding.date.opensSettings(SettingsActivity.SCREEN_CLOCK_WEATHER)
        binding.weather.opensSettings(SettingsActivity.SCREEN_CLOCK_WEATHER)
        binding.statusLine.opensSettings(SettingsActivity.SCREEN_APPEARANCE)
        binding.ticker.opensSettings(SettingsActivity.SCREEN_NOTIFICATIONS)
        binding.searchBar.opensSettings(SettingsActivity.SCREEN_GENERAL)
        // Deliberately NOT on suggestionsBlock: a long-clickable row would swallow
        // every touch starting on it, killing the paging swipe there — and a slow
        // swipe attempt used to long-press into Settings instead of switching apps.
        // (Suggestions settings stay reachable from the main Settings list.)
    }

    override fun onDestroy() {
        repo.removeListener(repoListener)
        NotifListener.removeListener(notifListener)
        ioExecutor.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        applyAppearance()
        resetToHome()
        updateStarterPill()
        refreshSuggestions()
        maybeRequestBluetoothPermission()
        maybeRequestCalendarPermission()
        checkForUpdate()
        setupStatusLine()
        NotifListener.ensureBound(this)
        startTicker()
        refreshWeather()
        refreshAgenda()
        registerAgendaObserver()
        updateNewAppSpot()
    }

    override fun onPause() {
        super.onPause()
        stopHints()
        stopTicker()
        stopNewAppSpot()
        unregisterStatusReceiver()
        unregisterAgendaObserver()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already home: return to the clean state.
        resetToHome()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // A launcher never finishes; back just closes overlays.
        if (anyDrawerOpen) {
            closeDrawers(animate = true)
        } else if (agendaShownByGesture) {
            agendaShownByGesture = false
            renderAgenda()
        } else if (allAppsOpen || binding.searchInput.text.isNotEmpty()) {
            resetToHome()
        }
    }

    // ---------------------------------------------------------------- setup

    private fun setupInsets() {
        var imeWasVisible = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            binding.content.setPadding(
                binding.content.paddingLeft, bars.top,
                binding.content.paddingRight, bars.bottom
            )
            // Whatever summons the keyboard takes over from the drawers.
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible && !imeWasVisible && anyDrawerOpen) closeDrawers(animate = true)
            imeWasVisible = imeVisible
            insets
        }
    }

    // Manual swipe tracking so we know the finger count, which GestureDetector hides.
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 1

    // Suggestion-swipe capture lives at dispatch level: the paging gesture must work
    // wherever it starts in the band — including ON the suggestion icons, which
    // consume touches and made row-level swipes die silently in the view hierarchy.
    private var suggDispatchCandidate = false
    private var suggDispatchClaimed = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                maxPointers = 1
                suggSwipeActive = false
                suggDispatchClaimed = false
                val edge = 40 * resources.displayMetrics.density
                suggDispatchCandidate =
                    binding.suggestionsBlock.visibility == View.VISIBLE &&
                    downInSuggestions() &&
                    ev.x > edge && ev.x < binding.root.width - edge // edges = drawer pulls
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                suggDispatchCandidate = false // two-finger swipes run gesture actions
            }
            MotionEvent.ACTION_MOVE -> {
                if (suggDispatchCandidate && !suggDispatchClaimed) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.2f) {
                        suggDispatchClaimed = true
                        // Whatever is under the finger (icon tap, long-press timer)
                        // must let go of the gesture.
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (suggDispatchClaimed) {
                    updateSuggestionSwipe(ev)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (suggDispatchClaimed) {
                    suggDispatchClaimed = false
                    finishSuggestionSwipe(
                        ev.x - downX, ev.x, ev.y,
                        confirmed = ev.actionMasked == MotionEvent.ACTION_UP,
                    )
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupGestures() {
        // Double-tap and long-press still come from GestureDetector; swipes are tracked
        // manually below to distinguish one- from two-finger horizontal gestures.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (prefs.doubleTapLock) lockScreen()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // A tap on the open home area dismisses whatever drawers are out.
                if (anyDrawerOpen) {
                    haptic(binding.root)
                    closeDrawers(animate = true)
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                haptic(binding.root)
                SettingsActivity.open(this@MainActivity)
            }
        })
        binding.root.isClickable = true
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val edgeZone = 40 * resources.displayMetrics.density
        binding.root.setOnTouchListener { v, event ->
            // ACTION_DOWN must be handled before the detector sees it: a pending drawer pull
            // disables long-press so a hesitant edge grab doesn't open Settings instead.
            // (downX/downY/downTime/maxPointers are recorded in dispatchTouchEvent, which
            // sees every stream — including the ones the suggestion icons consume.)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                drawerDragging = false
                drawerCandidateSide = drawerEdgeCandidate(event.x, v.width, edgeZone)
                // A hesitant start to a drawer pull or a suggestion swipe (claimed at
                // dispatch level) must not fire the long-press Settings shortcut.
                detector.setIsLongpressEnabled(
                    drawerCandidateSide == 0 && !suggDispatchCandidate
                )
                if (drawerCandidateSide != 0) {
                    velocityTracker = android.view.VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                }
            }
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // The drawer is a one-finger edge pull; a second finger cancels it.
                    drawerCandidateSide = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    updateDrawerDrag(event, touchSlop)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    if (drawerDragging) {
                        val vx = velocityTracker?.let {
                            it.addMovement(event)
                            it.computeCurrentVelocity(1000)
                            it.xVelocity
                        } ?: 0f
                        drawerForSide(drawerCandidateSide).settle(vx)
                        drawerDragging = false
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handleSwipe(event.x - downX, event.y - downY, event.eventTime - downTime)
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    drawerCandidateSide = 0
                }
            }
            true
        }
    }

    /**
     * Which side (if any) an edge touch could pull the drawer from. The interactive pull
     * is a one-finger gesture, so it engages when APP_DRAWER is bound to either slot of
     * that edge's direction — the finger count only matters for the fling path.
     */
    private fun drawerEdgeCandidate(x: Float, width: Int, edgeZone: Float): Int {
        // Only that edge's own drawer being open blocks a fresh pull; the other side
        // stays independently pullable (both drawers can be open at once).
        if (x < edgeZone && !binding.leftDrawer.isVisibleAtAll) {
            edgeDrawerIndex(Prefs.KEY_GESTURE_LR_1, Prefs.KEY_GESTURE_LR_2)?.let {
                drawerCandidateIndex = it
                return -1
            }
        }
        if (x > width - edgeZone && !binding.rightDrawer.isVisibleAtAll) {
            edgeDrawerIndex(Prefs.KEY_GESTURE_RL_1, Prefs.KEY_GESTURE_RL_2)?.let {
                drawerCandidateIndex = it
                return 1
            }
        }
        return 0
    }

    /** Drawer index bound to one of [keys], or null when none targets a drawer. */
    private fun edgeDrawerIndex(vararg keys: String): Int? = keys.firstNotNullOfOrNull {
        val spec = GestureBinding.decode(prefs.gestureBinding(it))
        if (spec.action == GestureAction.APP_DRAWER) spec.drawerIndex else null
    }

    private fun updateDrawerDrag(event: MotionEvent, touchSlop: Int) {
        if (drawerCandidateSide == 0) return
        val dx = event.x - downX
        if (!drawerDragging) {
            if (abs(dx) < touchSlop || abs(dx) <= abs(event.y - downY)) return
            // Must pull inward: left edge drags right, right edge drags left.
            val inward = (drawerCandidateSide < 0 && dx > 0) || (drawerCandidateSide > 0 && dx < 0)
            if (!inward) { drawerCandidateSide = 0; return }
            drawerDragging = true
            drawerForSide(drawerCandidateSide)
                .bind(drawerContents(drawerCandidateIndex), drawerCandidateIndex)
        }
        val drawer = drawerForSide(drawerCandidateSide)
        // Inward finger travel maps straight to the open amount.
        drawer.dragBy(if (drawerCandidateSide < 0) dx else -dx)
    }

    private fun handleSwipe(
        dx: Float, dy: Float, dtMs: Long,
        fingers: Int = maxPointers.coerceIn(1, 2),
        fromDrawer: Boolean = false,
    ) {
        if (dtMs > 700) return
        val density = resources.displayMetrics.density
        val minDist = 90 * density
        if (abs(dx) > abs(dy) && abs(dx) > minDist) {
            // Across the suggestions row, a horizontal swipe pages the proposed trio.
            // (Not for swipes forwarded by a drawer: those never started on the row.)
            if (!fromDrawer &&
                binding.suggestionsBlock.visibility == View.VISIBLE && downInSuggestions()
            ) {
                cycleSuggestions(if (dx < 0) 1 else -1)
                return
            }
            // A one-finger swipe matching an open drawer's closing motion closes it and
            // stops there; two-finger swipes always run their bound action instead.
            if (fingers == 1 && dx > 0 && binding.rightDrawer.isVisibleAtAll) {
                binding.rightDrawer.close(animate = true)
                return
            }
            if (fingers == 1 && dx < 0 && binding.leftDrawer.isVisibleAtAll) {
                binding.leftDrawer.close(animate = true)
                return
            }
            // Otherwise the bound action runs even while that side's drawer is open: a
            // drawer action swaps the wheel in place, a system action leaves it open.
            val key = when {
                dx > 0 && fingers == 1 -> Prefs.KEY_GESTURE_LR_1
                dx > 0 -> Prefs.KEY_GESTURE_LR_2
                fingers == 1 -> Prefs.KEY_GESTURE_RL_1
                else -> Prefs.KEY_GESTURE_RL_2
            }
            // A left→right swipe pulls the drawer from the left, and vice versa.
            runGesture(GestureBinding.decode(prefs.gestureBinding(key)), drawerSide = if (dx > 0) -1 else 1)
        } else if (abs(dy) > abs(dx) && abs(dy) > minDist) {
            // Vertical stays fixed: up = all apps, down = notifications.
            if (dy < 0) {
                haptic(binding.root)
                openAllApps()
            } else if (prefs.swipeDownNotifications) {
                haptic(binding.root)
                LockService.openNotificationShade(this)
            }
        }
    }

    /**
     * True when the gesture started in the suggestion-paging band: the row itself
     * (including the icons — dispatch-level capture makes those work) plus a
     * reach margin above it. Anchored to the row, NOT to a screen fraction:
     * anything higher keeps running the bound one-finger `>`/`>>` gestures.
     * Two-finger swipes and edge pulls are excluded upstream.
     */
    private fun downInSuggestions(): Boolean {
        val loc = IntArray(2)
        binding.suggestionsBlock.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        binding.root.getLocationInWindow(rootLoc)
        val blockTop = (loc[1] - rootLoc[1]).toFloat()
        val top = blockTop - 72 * resources.displayMetrics.density
        return downY >= top && downY <= blockTop + binding.suggestionsBlock.height
    }

    private fun runGesture(bindingSpec: GestureBinding, drawerSide: Int = -1) {
        haptic(binding.root)
        when (bindingSpec.action) {
            GestureAction.NONE -> {}
            GestureAction.NOTIFICATIONS -> LockService.openNotificationShade(this)
            GestureAction.QUICK_SETTINGS ->
                if (!LockService.openQuickSettings()) LockService.openNotificationShade(this)
            GestureAction.LOCK -> lockScreen()
            GestureAction.FLASHLIGHT -> toggleFlashlight()
            GestureAction.ASSISTANT -> launchAssistant()
            GestureAction.ALL_APPS -> openAllApps()
            GestureAction.APP_DRAWER ->
                openDrawer(drawerSide, bindingSpec.drawerIndex, animate = true)
            GestureAction.SEARCH -> focusSearch(show = true)
            GestureAction.CAMERA -> startActivitySafely(
                Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            )
            GestureAction.DIALER -> startActivitySafely(Intent(Intent.ACTION_DIAL))
            GestureAction.AGENDA -> toggleAgenda()
            GestureAction.OPEN_APP ->
                bindingSpec.appKey?.let { key ->
                    repo.byComponentKey(key)?.let { launchApp(it, binding.root) }
                }
        }
    }

    // ------------------------------------------------------------- drawers

    // Two independent, non-modal wheel drawers. During an edge pull-in, these track the
    // finger for the side being dragged and which configured drawer it opens.
    private var drawerCandidateSide = 0  // edge the current touch could pull a drawer from
    private var drawerCandidateIndex = 0 // configured drawer bound to that edge
    private var drawerDragging = false
    private var velocityTracker: android.view.VelocityTracker? = null

    private fun setupDrawer() {
        for (drawer in listOf(binding.leftDrawer to -1, binding.rightDrawer to 1)) {
            val view = drawer.first
            view.onAppDropped = { key, from, to, position -> handleAppDrop(key, from, to, position) }
            view.onItemDragStarted = { entry, v -> onAppDragStarted(entry, v) }
            view.onDragMoved = { x, y -> dragLastX = x; dragLastY = y }
            // Swipes the drawer doesn't own (inward, or multi-finger) still run their
            // bound action: `>>` from the left must work while the left drawer is out.
            view.onSwipe = { dx, dy, dtMs, fingers ->
                handleSwipe(dx, dy, dtMs, fingers.coerceIn(1, 2), fromDrawer = true)
            }
            view.init(
                side = drawer.second,
                iconOf = { entry -> repo.icon(entry) },
                badgeOf = { pkg -> badgeFor(pkg) },
                onClick = { entry, v -> view.close(animate = false); launchApp(entry, v) },
                onLongClick = { _, v -> haptic(v) }, // drag pickup feedback
                onClosed = { haptic(binding.root) },
                onOpened = { haptic(binding.root) }, // the wheel clicks into place
                onVisibleChanged = { visible ->
                    // The gesture hint on a drawer's side yields to the drawer.
                    val hint = if (drawer.second < 0) binding.hintLeft else binding.hintRight
                    if (visible) hint.visibility = View.INVISIBLE else startHints()
                    updateNewAppSpot()
                    renderAgenda() // the stream yields to open drawers too
                    // Drop the keyboard while a drawer is out so the command bar sits
                    // at the bottom instead of riding up into the wheel; bring it back
                    // once every drawer is gone.
                    if (visible) {
                        hideKeyboard()
                    } else if (!anyDrawerOpen && prefs.keyboardAlways && !allAppsOpen) {
                        focusSearch(show = true)
                    }
                },
            )
        }
        // The wheel occupies half the screen height, vertically centered.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val half = binding.root.height / 2
            if (half <= 0) return@addOnLayoutChangeListener
            for (drawer in listOf(binding.leftDrawer, binding.rightDrawer)) {
                val lp = drawer.layoutParams as android.widget.FrameLayout.LayoutParams
                if (lp.height != half) {
                    lp.height = half
                    drawer.layoutParams = lp
                }
            }
        }
    }

    private fun drawerForSide(side: Int): WheelDrawer =
        if (side < 0) binding.leftDrawer else binding.rightDrawer

    private val anyDrawerOpen: Boolean
        get() = binding.leftDrawer.isVisibleAtAll || binding.rightDrawer.isVisibleAtAll

    private fun closeDrawers(animate: Boolean) {
        if (binding.leftDrawer.isVisibleAtAll) binding.leftDrawer.close(animate)
        if (binding.rightDrawer.isVisibleAtAll) binding.rightDrawer.close(animate)
    }

    private fun drawerContents(index: Int): List<AppEntry> {
        val keys = prefs.drawer(index).apps
        return if (keys.isEmpty()) repo.visibleApps(prefs.hiddenApps)
        else keys.mapNotNull { repo.byComponentKey(it) }
    }

    private fun openDrawer(side: Int, index: Int, animate: Boolean) {
        val drawer = drawerForSide(side)
        val anim = animate && prefs.animations
        if (anim && drawer.isVisibleAtAll && drawer.boundIndex != index) {
            // A different drawer already out on this side: classic close, then
            // classic open with the new content — not a silent in-place swap.
            drawer.swapTo(drawerContents(index), index)
        } else {
            drawer.bind(drawerContents(index), index)
            drawer.open(anim)
        }
    }

    /** Applies a drag & drop: reorder within a drawer, or move/copy into another. */
    private fun handleAppDrop(componentKey: String, from: Int, to: Int, position: Int): Boolean {
        val target = prefs.drawer(to)
        if (target.apps.isEmpty()) {
            // "All apps" drawers have no explicit order to edit.
            Toast.makeText(this, R.string.drawer_drop_needs_list, Toast.LENGTH_LONG).show()
            return false
        }
        val list = target.apps.toMutableList()
        val oldIndex = list.indexOf(componentKey)
        list.remove(componentKey)
        // Removing an earlier occurrence shifts the insertion point left by one.
        val insertAt = (if (oldIndex in 0 until position) position - 1 else position)
            .coerceIn(0, list.size)
        list.add(insertAt, componentKey)
        prefs.saveDrawer(to, target.name, list)
        if (from >= 0 && from != to) {
            val source = prefs.drawer(from)
            if (source.apps.isNotEmpty()) {
                prefs.saveDrawer(from, source.name, source.apps - componentKey)
            }
        }
        haptic(binding.root)
        refreshOpenDrawers()
        // Landing pop on the freshly inserted item.
        if (prefs.animations) {
            for (drawer in listOf(binding.leftDrawer, binding.rightDrawer)) {
                if (drawer.isVisibleAtAll && drawer.boundIndex == to) drawer.popItem(insertAt)
            }
        }
        return true
    }

    /** Re-reads the visible drawers' contents after a drag & drop mutation. */
    private fun refreshOpenDrawers() {
        for (drawer in listOf(binding.leftDrawer, binding.rightDrawer)) {
            if (drawer.isVisibleAtAll) {
                drawer.bind(drawerContents(drawer.boundIndex), drawer.boundIndex)
            }
        }
    }

    private fun setupSearch() {
        adapter = AppAdapter(
            repo,
            onAppClick = { entry, view -> launchApp(entry, view) },
            onAppLongClick = { entry, view -> showAppMenu(entry, view) },
            onCommand = { command -> runCommand(command) },
            badgeCount = { pkg -> badgeFor(pkg) },
        )
        searchMode = SearchMode.byId(prefs.searchModeId)
        binding.results.layoutManager = LinearLayoutManager(this).apply {
            // Best match sits at the bottom, right above the keyboard.
            reverseLayout = true
        }
        binding.results.adapter = adapter
        binding.results.itemAnimator = null

        binding.searchInput.doAfterTextChanged { text ->
            onQueryChanged(text?.toString().orEmpty())
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = binding.searchInput.text.toString().trim()
                when (val row = adapter.primaryRow()) {
                    is AppAdapter.AppRow -> launchApp(row.entry, binding.searchInput)
                    is AppAdapter.CommandRow -> runCommand(row.command)
                    // Nothing matched: only the assistant-oriented modes fall through to it.
                    null -> if (query.isNotEmpty() &&
                        (searchMode == SearchMode.SMART || searchMode == SearchMode.ASK)
                    ) launchAssistant(query)
                }
                true
            } else {
                false
            }
        }
        // Touching the command bar takes over from any open drawer.
        binding.searchInput.setOnClickListener { closeDrawers(animate = true) }
        // Left button is the mode wheel: tap to cycle, long-press for the assistant.
        binding.modeBtn.setOnClickListener { cycleMode() }
        binding.modeBtn.setOnLongClickListener { launchAssistant(); true }
        binding.allAppsBtn.setOnClickListener {
            haptic(binding.allAppsBtn)
            if (allAppsOpen) resetToHome() else openAllApps()
        }
    }

    private fun cycleMode() {
        searchMode = searchMode.next()
        prefs.searchModeId = searchMode.id
        haptic(binding.modeBtn)
        updateModeUi()
        Toast.makeText(
            this, getString(R.string.mode_switched, getString(searchMode.labelRes)),
            Toast.LENGTH_SHORT
        ).show()
        onQueryChanged(binding.searchInput.text.toString())
    }

    private fun updateModeUi() {
        binding.modeBtn.setImageResource(searchMode.iconRes)
        binding.modeBtn.setColorFilter(ColorUtils.setAlphaComponent(accentColor(), 0xCC))
        binding.searchInput.hint = getString(
            when (searchMode) {
                SearchMode.SMART -> R.string.search_hint
                SearchMode.APPS -> R.string.hint_apps_mode
                SearchMode.SETTINGS -> R.string.hint_settings_mode
                SearchMode.ASK -> R.string.hint_ask_mode
            }
        )
    }

    private fun setupSuggestionClicks() {
        binding.suggestMain.setOnClickListener { v -> suggestionAt(0)?.let { launchApp(it, v) } }
        binding.suggestLeft.setOnClickListener { v -> suggestionAt(1)?.let { launchApp(it, v) } }
        binding.suggestRight.setOnClickListener { v -> suggestionAt(2)?.let { launchApp(it, v) } }
        // Long-press: menu normally; with a drawer open, start a drag into it instead.
        fun longPress(rank: Int, v: View): Boolean {
            val entry = suggestionAt(rank) ?: return true
            if (anyDrawerOpen) startAppDrag(entry, v, fromDrawer = -1)
            else showAppMenu(entry, v)
            return true
        }
        binding.suggestMain.setOnLongClickListener { v -> longPress(0, v) }
        binding.suggestLeft.setOnLongClickListener { v -> longPress(1, v) }
        binding.suggestRight.setOnLongClickListener { v -> longPress(2, v) }
    }

    /** Starts a system drag carrying the app; open drawers accept the drop. */
    private fun startAppDrag(entry: AppEntry, sourceView: View, fromDrawer: Int) {
        haptic(sourceView)
        onAppDragStarted(entry, sourceView)
        val data = android.content.ClipData.newPlainText("app", entry.componentKey)
        sourceView.startDragAndDrop(
            data, WheelDrawer.IconShadow(sourceView, 1.4f),
            WheelDrawer.DragPayload(entry.componentKey, fromDrawer), 0
        )
    }

    // ------------------------------------------------------- drag lifecycle

    // The lifted app dims at its origin; a rejected drop flies the icon back home.
    private var dragSourceView: View? = null
    private var dragIcon: android.graphics.drawable.Drawable? = null
    private var dragLastX = 0f
    private var dragLastY = 0f

    private fun onAppDragStarted(entry: AppEntry, sourceView: View) {
        dragSourceView = sourceView
        dragIcon = repo.icon(entry)
        sourceView.animate().alpha(0.25f).setDuration(150).start()
    }

    /** Root-level drag watcher: tracks the finger and settles the ending. */
    private fun setupDragWatcher() {
        binding.root.setOnDragListener { _, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    dragLastX = event.x
                    dragLastY = event.y
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    onDragEnded(event.result)
                    true
                }
                else -> true
            }
        }
    }

    private fun onDragEnded(success: Boolean) {
        val source = dragSourceView ?: return
        dragSourceView = null
        // A rejected drop deserves feedback too — the accepted path buzzes in
        // handleAppDrop, so without this a failed drop just feels dead.
        if (!success) haptic(binding.root)
        if (success || !prefs.animations || dragIcon == null) {
            source.animate().alpha(1f).setDuration(150).start()
            return
        }
        // Rejected: fly a ghost of the icon from the drop point back to its origin.
        val ghost = binding.dragGhost
        val half = ghost.layoutParams.width / 2f
        val loc = IntArray(2)
        source.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        binding.root.getLocationInWindow(rootLoc)
        ghost.setImageDrawable(dragIcon)
        ghost.x = dragLastX - half
        ghost.y = dragLastY - half
        ghost.alpha = 1f
        ghost.visibility = View.VISIBLE
        ghost.animate()
            .x((loc[0] - rootLoc[0] + source.width / 2f) - half)
            .y((loc[1] - rootLoc[1] + source.height / 2f) - half)
            .setDuration(320)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                ghost.visibility = View.GONE
                source.animate().alpha(1f).setDuration(120).start()
            }
            .start()
    }

    private var suggestions: List<AppEntry?> = listOf(null, null, null)

    private fun suggestionAt(rank: Int): AppEntry? = suggestions.getOrNull(rank)

    // ------------------------------------------------------------- behavior

    private fun onQueryChanged(raw: String) {
        val query = raw.trim()
        val mode = searchMode
        // Typing means the user moved on from the drawers.
        if (query.isNotEmpty() && anyDrawerOpen) closeDrawers(animate = true)
        // Empty with no active view is the home screen — suggestions + hints, mode-independent.
        if (query.isEmpty() && !allAppsOpen) {
            binding.results.visibility = View.GONE
            binding.suggestionsBlock.visibility = View.VISIBLE
            startHints()
            renderAgenda()
            return
        }

        // "All apps" is a dedicated affordance: it always lists (and filters) apps, whatever
        // the command-bar mode, and shows no command rows. Otherwise the mode governs.
        val listApps = allAppsOpen || mode == SearchMode.SMART || mode == SearchMode.APPS
        val apps = when {
            !listApps -> emptyList()
            query.isEmpty() -> repo.visibleApps(prefs.hiddenApps).reversed()
            else -> repo.search(query, prefs.hiddenApps)
        }
        val commands = if (allAppsOpen) emptyList() else buildCommands(query, mode, apps.size)
        adapter.submit(apps, commands)

        binding.results.visibility = View.VISIBLE
        binding.suggestionsBlock.visibility = View.INVISIBLE
        binding.agenda.visibility = View.GONE
        binding.results.scrollToPosition(0)
        stopHints()
        binding.hintLeft.visibility = View.GONE
        binding.hintRight.visibility = View.GONE
        updateNewAppSpot()
    }

    /** Command rows for the current mode, including the "ask the assistant" row. */
    private fun buildCommands(
        query: String, mode: SearchMode, appMatches: Int,
    ): List<CommandProcessor.Command> {
        if (mode == SearchMode.ASK) {
            if (query.isEmpty()) return emptyList()
            return listOf(assistCommand(query))
        }
        val base = CommandProcessor.parse(
            query, mode, commandCatalog, R.drawable.ic_calc, R.drawable.ic_link
        )
        if (mode == SearchMode.SMART && query.isNotEmpty()) {
            // Route sentences/questions — or anything with no other match — to the assistant.
            val wantAssist = CommandProcessor.isNaturalLanguage(query) ||
                (appMatches == 0 && base.isEmpty())
            if (wantAssist) return base + assistCommand(query)
        }
        return base
    }

    private fun assistCommand(query: String) = CommandProcessor.Command(
        R.drawable.ic_chat,
        getString(R.string.cmd_ask),
        query,
        CommandProcessor.Action.Assist(query),
    )

    private fun openAllApps() {
        allAppsOpen = true
        closeDrawers(animate = true)
        onQueryChanged(binding.searchInput.text.toString())
        focusSearch(show = true)
    }

    private fun resetToHome() {
        allAppsOpen = false
        agendaShownByGesture = false
        closeDrawers(animate = false)
        if (binding.searchInput.text.isNotEmpty()) {
            binding.searchInput.setText("")
        }
        binding.results.visibility = View.GONE
        binding.suggestionsBlock.visibility = View.VISIBLE
        startHints()
        renderAgenda()
        updateNewAppSpot()
        if (prefs.keyboardAlways) {
            focusSearch(show = true)
        } else {
            hideKeyboard()
        }
    }

    private fun launchApp(entry: AppEntry, sourceView: View?) {
        haptic(sourceView ?: binding.root)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = if (prefs.animations && sourceView != null) {
            ActivityOptions.makeScaleUpAnimation(
                sourceView, 0, 0, sourceView.width, sourceView.height
            ).toBundle()
        } else {
            null
        }
        try {
            startActivity(intent, options)
        } catch (e: Exception) {
            // Stale entry (app updated/removed a second ago): refresh and bail.
            repo.load()
            Toast.makeText(this, entry.label, Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.predictions) PredictionEngine.logLaunch(this, entry.packageName)
        // Clear the query once we're out of sight so the return feels instant.
        binding.root.postDelayed({ resetToHome() }, 400)
    }

    /**
     * Opens the assistant. When [query] is given (Ask mode / "ask the assistant" row),
     * the text is carried along: Android has no universal "ask the assistant this text"
     * intent, so we deliver it via the query-bearing search intent the Google app and
     * most assistants handle, falling back to the plain voice/assist entry points.
     */
    private fun launchAssistant(query: String? = null) {
        haptic(binding.modeBtn)
        val q = query?.trim().orEmpty()
        val candidates = if (q.isNotEmpty()) {
            listOf(
                Intent(Intent.ACTION_ASSIST).putExtra(SearchManager.QUERY, q),
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
                Intent(Intent.ACTION_VOICE_COMMAND),
            )
        } else {
            listOf(
                Intent(Intent.ACTION_VOICE_COMMAND),
                Intent(Intent.ACTION_ASSIST),
                Intent(Intent.ACTION_WEB_SEARCH),
            )
        }
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
    }

    // ------------------------------------------------------------- commands

    /** Quick actions and settings destinations, matched by name in the command bar. */
    private val commandCatalog: List<CommandProcessor.QuickAction> by lazy {
        listOf(
            // Toggles / actions
            CommandProcessor.QuickAction("lock", getString(R.string.cmd_lock), R.drawable.ic_lock, "lock secure screen"),
            CommandProcessor.QuickAction("quick_settings", getString(R.string.cmd_quick_settings), R.drawable.ic_tiles, "toggles panel"),
            CommandProcessor.QuickAction("notifications", getString(R.string.cmd_notifications), R.drawable.ic_bell, "shade alerts"),
            CommandProcessor.QuickAction("flashlight", getString(R.string.cmd_flashlight), R.drawable.ic_flashlight, "torch light lamp"),
            CommandProcessor.QuickAction("assistant", getString(R.string.cmd_assistant), R.drawable.ic_assistant, "gemini voice"),
            CommandProcessor.QuickAction("all_apps", getString(R.string.cmd_all_apps), R.drawable.ic_apps, "drawer list"),
            CommandProcessor.QuickAction("launcher_settings", getString(R.string.cmd_launcher_settings), R.drawable.ic_settings, "launcher lastlauncher home options preferences settings parametres paramètres reglages réglages"),
            // Settings destinations
            CommandProcessor.QuickAction("wifi", getString(R.string.cmd_wifi), R.drawable.ic_wifi, "wifi network internet"),
            CommandProcessor.QuickAction("bluetooth", getString(R.string.cmd_bluetooth), R.drawable.ic_bluetooth, "bluetooth bt"),
            CommandProcessor.QuickAction("battery", getString(R.string.cmd_battery), R.drawable.ic_battery, "battery power"),
            CommandProcessor.QuickAction("display", getString(R.string.cmd_display), R.drawable.ic_settings, "display screen brightness"),
            CommandProcessor.QuickAction("sound", getString(R.string.cmd_sound), R.drawable.ic_settings, "sound volume audio"),
            CommandProcessor.QuickAction("apps_settings", getString(R.string.cmd_apps_settings), R.drawable.ic_settings, "apps applications"),
            CommandProcessor.QuickAction("storage", getString(R.string.cmd_storage), R.drawable.ic_settings, "storage space memory"),
            CommandProcessor.QuickAction("location", getString(R.string.cmd_location), R.drawable.ic_settings, "location gps"),
            CommandProcessor.QuickAction("security", getString(R.string.cmd_security), R.drawable.ic_lock, "security lock"),
            CommandProcessor.QuickAction("airplane", getString(R.string.cmd_airplane), R.drawable.ic_settings, "airplane flight mode"),
            CommandProcessor.QuickAction("datetime", getString(R.string.cmd_datetime), R.drawable.ic_clock, "date time clock"),
            CommandProcessor.QuickAction("language", getString(R.string.cmd_language), R.drawable.ic_settings, "language locale"),
            CommandProcessor.QuickAction("about", getString(R.string.cmd_about), R.drawable.ic_settings, "about phone device info"),
            CommandProcessor.QuickAction("settings", getString(R.string.cmd_settings), R.drawable.ic_settings, "system android settings"),
        )
    }

    private fun runCommand(command: CommandProcessor.Command) {
        haptic(binding.searchInput)
        when (val action = command.action) {
            is CommandProcessor.Action.Copy -> {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("result", action.text)
                )
                Toast.makeText(
                    this, getString(R.string.cmd_copied, action.text), Toast.LENGTH_SHORT
                ).show()
            }
            is CommandProcessor.Action.OpenUrl -> openUrl(action.url)
            is CommandProcessor.Action.Quick -> runQuickAction(action.id)
            is CommandProcessor.Action.Assist -> launchAssistant(action.query)
        }
    }

    private fun runQuickAction(id: String) {
        when (id) {
            "lock" -> lockScreen()
            "quick_settings" -> if (!LockService.openQuickSettings()) LockService.openNotificationShade(this)
            "notifications" -> LockService.openNotificationShade(this)
            "flashlight" -> toggleFlashlight()
            "assistant" -> launchAssistant()
            "all_apps" -> openAllApps()
            "launcher_settings" -> SettingsActivity.open(this)
            "wifi" -> openSettingsAction(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)
            "battery" -> openSettingsAction(Intent.ACTION_POWER_USAGE_SUMMARY)
            "display" -> openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> openSettingsAction(Settings.ACTION_SOUND_SETTINGS)
            "apps_settings" -> openSettingsAction(Settings.ACTION_APPLICATION_SETTINGS)
            "storage" -> openSettingsAction(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "location" -> openSettingsAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "security" -> openSettingsAction(Settings.ACTION_SECURITY_SETTINGS)
            "airplane" -> openSettingsAction(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "datetime" -> openSettingsAction(Settings.ACTION_DATE_SETTINGS)
            "language" -> openSettingsAction(Settings.ACTION_LOCALE_SETTINGS)
            "about" -> openSettingsAction(Settings.ACTION_DEVICE_INFO_SETTINGS)
            "settings" -> openSettingsAction(Settings.ACTION_SETTINGS)
        }
        if (id != "all_apps") resetToHome()
    }

    private fun openSettingsAction(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                // no settings app resolvable
            }
        }
    }

    private var torchOn = false

    private fun toggleFlashlight() {
        val manager = getSystemService(CameraManager::class.java) ?: return
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            manager.setTorchMode(cameraId, torchOn)
        } catch (e: Exception) {
            torchOn = false
        }
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // nothing resolves this intent on the device
        }
    }

    private fun openUrl(url: String) {
        startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Tapping the clock opens the configured app (system clock by default). */
    private fun launchClockTarget() {
        haptic(binding.clock)
        val target = prefs.clockTapTarget
        if (target.isNotEmpty()) {
            repo.byComponentKey(target)?.let {
                launchApp(it, binding.clock)
                return
            }
        }
        startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    // -------------------------------------------------------------- weather

    private fun refreshWeather() {
        if (!prefs.weatherEnabled) {
            binding.weather.visibility = View.GONE
            styleClockForWeather(null)
            return
        }
        // Ask once; the user grants location from the Weather settings screen.
        WeatherProvider.request(this) { weather -> renderWeather(weather) }
        if (!WeatherProvider.hasLocationPermission(this) &&
            binding.weather.visibility != View.VISIBLE
        ) {
            binding.weather.visibility = View.GONE
        }
    }

    private fun renderWeather(weather: WeatherProvider.Weather?) {
        if (!prefs.weatherEnabled || weather == null) {
            binding.weather.visibility = View.GONE
            styleClockForWeather(null)
            return
        }
        placeWeatherChip()
        val fahrenheit = prefs.weatherUnits == "f"
        val temp = if (fahrenheit) weather.tempC * 9 / 5 + 32 else weather.tempC
        val degrees = "${Math.round(temp)}°"
        val glyph = WeatherProvider.glyph(weather.code)
        binding.weather.text = when (prefs.weatherStyle) {
            "temp" -> degrees
            "icon" -> glyph
            else -> "$glyph $degrees"
        }
        binding.weather.setTextColor(accentColor())
        binding.weather.visibility = View.VISIBLE
        styleClockForWeather(if (prefs.weatherClockStyle) weather.code else null)
    }

    /**
     * Moves the weather chip beside the clock or back under the date, per setting.
     * On the clock line it grows into a real companion of the clock — big, thin
     * digits-style type — instead of a tiny footnote.
     */
    private fun placeWeatherChip() {
        val wantRow = prefs.weatherBesideClock
        val inRow = binding.weather.parent === binding.clockRow
        if (wantRow != inRow) {
            (binding.weather.parent as? android.view.ViewGroup)?.removeView(binding.weather)
            if (wantRow) {
                binding.clockRow.addView(binding.weather)
                (binding.weather.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    marginStart = (16 * resources.displayMetrics.density).toInt()
                    topMargin = 0
                }
            } else {
                // Back to its original slot: right after the date line.
                val index = binding.content.indexOfChild(binding.date) + 1
                binding.content.addView(binding.weather, index)
                (binding.weather.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                    marginStart = 0
                }
            }
        }
        binding.weather.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (wantRow) 30f else 16f)
        binding.weather.typeface = android.graphics.Typeface.create(
            if (wantRow) "sans-serif-light" else "sans-serif", android.graphics.Typeface.NORMAL
        )
    }

    /**
     * "Living clock": the digits take on the sky's hue — warm gold in sunshine, steel
     * blue in rain, icy white in snow, violet in storms — backed by a matching glow.
     * Static styling set once per weather refresh, so the battery cost is zero.
     */
    private fun styleClockForWeather(weatherCode: Int?) {
        if (weatherCode == null) {
            binding.clock.setShadowLayer(0f, 0f, 0f, 0)
            binding.clock.setTextColor(getColor(R.color.text_primary))
            return
        }
        // Every hue is saturated — a near-white "silver" blended into white digits was
        // invisible, which is why the effect looked dead in cloudy weather.
        val hue = when (weatherCode) {
            0, 1 -> 0xFFFFB733.toInt()          // sun: golden
            2, 3 -> 0xFF7E96C8.toInt()          // clouds: slate blue
            in 45..48 -> 0xFF8A93A6.toInt()     // fog: grey-blue
            in 51..67, in 80..82 -> 0xFF3D9BFF.toInt() // rain: azure
            in 71..77, in 85..86 -> 0xFF6FC5FF.toInt() // snow: ice blue
            in 95..99 -> 0xFF9E6FFF.toInt()     // storm: violet
            else -> 0xFF7E96C8.toInt()
        }
        // Color blend carries the effect (clearly visible); the glow reinforces it.
        // Radius stays ≤25px: larger text-shadow radii are unreliable when
        // hardware-accelerated on older devices.
        binding.clock.setTextColor(
            ColorUtils.blendARGB(getColor(R.color.text_primary), hue, 0.6f)
        )
        binding.clock.setShadowLayer(
            24f, 0f, 0f, ColorUtils.setAlphaComponent(hue, 0xD0)
        )
    }

    /** Tapping the weather chip opens the chosen app, else a weather web search. */
    private fun launchWeatherTarget() {
        haptic(binding.weather)
        val target = prefs.weatherTapTarget
        if (target.isNotEmpty()) {
            repo.byComponentKey(target)?.let {
                launchApp(it, binding.weather)
                return
            }
        }
        openUrl("https://www.google.com/search?q=" + Uri.encode(getString(R.string.weather_query)))
    }

    /**
     * One-time cold-start helper: lets the user pick go-to apps that fill the
     * suggestion slots until the prediction engine has learned enough. Pre-checks
     * sensible guesses (phone, SMS, browser, camera).
     */
    private fun showStarterPicker() {
        val apps = repo.visibleApps(prefs.hiddenApps)
        if (apps.isEmpty() || ioExecutor.isShutdown) return
        ioExecutor.execute {
            // defaultPicks resolves phone/SMS/browser/camera via PackageManager — IPC,
            // so not UI-thread work.
            val current = prefs.favorites.ifEmpty { PredictionEngine.defaultPicks(this) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
                val checked = BooleanArray(apps.size) { apps[it].packageName in current }
                AppPickerDialog.multiChoice(
                    this, getString(R.string.starter_dialog_title), items, checked
                ) {
                    prefs.favorites =
                        apps.indices.filter { checked[it] }.map { apps[it].packageName }
                    prefs.onboardingDone = true
                    updateStarterPill()
                    refreshSuggestions()
                }
            }
        }
    }

    // -------------------------------------------------------- gesture hints

    // Each edge cycles between its one- and two-finger bindings. One cycle = the hint
    // travels its short course in the swipe's direction while fading in, cruising,
    // then fading out at the end of the course; then the texts advance (`>` → `>>`).
    private val hintHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hintStep = 0
    private var hintAnimator: android.animation.ValueAnimator? = null

    private fun startHints() {
        stopHints()
        val show = prefs.showGestureHints &&
            binding.results.visibility != View.VISIBLE
        if (!show || !hasAnyHint()) {
            binding.hintLeft.visibility = View.GONE
            binding.hintRight.visibility = View.GONE
            return
        }
        hintStep = 0
        if (!prefs.animations) {
            // Static fallback: show the one-finger bindings, no motion, no cycling.
            setHintText(binding.hintLeft, leftEdgeText(Prefs.KEY_GESTURE_LR_1, 1), 0.85f)
            setHintText(binding.hintRight, rightEdgeText(Prefs.KEY_GESTURE_RL_1, 1), 0.85f)
            return
        }
        runHintCycle()
    }

    private fun stopHints() {
        hintHandler.removeCallbacksAndMessages(null)
        hintAnimator?.let {
            it.removeAllUpdateListeners()
            it.removeAllListeners()
            it.cancel()
        }
        hintAnimator = null
        binding.hintLeft.translationX = 0f
        binding.hintRight.translationX = 0f
    }

    private fun hasAnyHint(): Boolean = listOf(
        Prefs.KEY_GESTURE_LR_1, Prefs.KEY_GESTURE_LR_2,
        Prefs.KEY_GESTURE_RL_1, Prefs.KEY_GESTURE_RL_2,
    ).any { bindingLabel(prefs.gestureBinding(it)) != null }

    /**
     * One travel-and-fade pass for the current step, then advance. The alpha envelope
     * (in → cruise → out) plays over the same slow slide, so the hint dies exactly at
     * the end of its course — like a passing light trail.
     */
    private fun runHintCycle() {
        val arrows = if (hintStep == 0) 1 else 2
        val leftKey = if (hintStep == 0) Prefs.KEY_GESTURE_LR_1 else Prefs.KEY_GESTURE_LR_2
        val rightKey = if (hintStep == 0) Prefs.KEY_GESTURE_RL_1 else Prefs.KEY_GESTURE_RL_2
        // An open drawer owns its edge: no hint runs behind it. (The cycle keeps
        // running so the hint returns as soon as the drawer closes.)
        val hasLeft = if (binding.leftDrawer.isVisibleAtAll) {
            binding.hintLeft.visibility = View.GONE
            false
        } else {
            setHintText(binding.hintLeft, leftEdgeText(leftKey, arrows), 0f)
        }
        val hasRight = if (binding.rightDrawer.isVisibleAtAll) {
            binding.hintRight.visibility = View.GONE
            false
        } else {
            setHintText(binding.hintRight, rightEdgeText(rightKey, arrows), 0f)
        }
        if (!hasLeft && !hasRight) {
            // This step is unbound on both edges; show the other one next pass.
            hintStep = hintStep xor 1
            hintHandler.postDelayed({ runHintCycle() }, 250)
            return
        }
        val travel = 26 * resources.displayMetrics.density
        hintAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val alpha = 0.85f * when {
                    f < 0.22f -> f / 0.22f            // fade in at the start of the course
                    f > 0.72f -> (1f - f) / 0.28f     // fade out as it reaches the end
                    else -> 1f
                }
                if (hasLeft) {
                    binding.hintLeft.translationX = travel * f
                    binding.hintLeft.alpha = alpha
                }
                if (hasRight) {
                    binding.hintRight.translationX = -travel * f
                    binding.hintRight.alpha = alpha
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    hintStep = hintStep xor 1
                    hintHandler.postDelayed({ runHintCycle() }, 250)
                }
            })
            start()
        }
    }

    /** Applies text/color to a hint; null text hides it. Returns whether it is shown. */
    private fun setHintText(view: TextView, text: String?, alpha: Float): Boolean {
        if (text == null) {
            view.visibility = View.GONE
            return false
        }
        view.setTextColor(ColorUtils.setAlphaComponent(accentColor(), 0xB0))
        view.text = text
        view.alpha = alpha
        view.translationX = 0f
        view.visibility = View.VISIBLE
        return true
    }

    private fun leftEdgeText(key: String, arrows: Int): String? {
        val label = bindingLabel(prefs.gestureBinding(key)) ?: return null
        return "${">".repeat(arrows)} $label"
    }

    private fun rightEdgeText(key: String, arrows: Int): String? {
        val label = bindingLabel(prefs.gestureBinding(key)) ?: return null
        return "$label ${"<".repeat(arrows)}"
    }

    /** Human label for an encoded binding, or null for NONE (hint hidden). */
    private fun bindingLabel(encoded: String): String? {
        val spec = GestureBinding.decode(encoded)
        return when (spec.action) {
            GestureAction.NONE -> null
            GestureAction.OPEN_APP -> spec.appKey?.let { repo.byComponentKey(it) }?.label
            GestureAction.APP_DRAWER -> prefs.drawer(spec.drawerIndex).name
            else -> getString(spec.action.labelRes)
        }
    }

    private fun updateStarterPill() {
        binding.starterPill.visibility =
            if (prefs.predictions && !prefs.onboardingDone) View.VISIBLE else View.GONE
    }

    private fun lockScreen() {
        if (LockService.lockScreen()) {
            haptic(binding.root)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_needs_service_title)
            .setMessage(R.string.lock_needs_service_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                LockService.openAccessibilitySettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppMenu(entry: AppEntry, anchor: View) {
        haptic(anchor)
        val boosted = entry.packageName in prefs.boostedApps
        fun icon(res: Int) = androidx.core.content.ContextCompat.getDrawable(this, res)
        val items = listOf(
            AppPickerDialog.Item(
                getString(if (boosted) R.string.menu_unboost else R.string.menu_boost),
                icon(R.drawable.ic_boost)
            ),
            AppPickerDialog.Item(getString(R.string.menu_add_to_drawer), icon(R.drawable.ic_apps)),
            AppPickerDialog.Item(getString(R.string.menu_app_info), icon(R.drawable.ic_info)),
            AppPickerDialog.Item(getString(R.string.menu_hide), icon(R.drawable.ic_eye_off)),
            AppPickerDialog.Item(getString(R.string.menu_uninstall), icon(R.drawable.ic_delete)),
        )
        AppPickerDialog.singleChoice(this, entry.label, items, -1) { which ->
            when (which) {
                0 -> {
                    prefs.boostedApps =
                        if (boosted) prefs.boostedApps - entry.packageName
                        else prefs.boostedApps + entry.packageName
                    refreshSuggestions()
                }
                1 -> pickDrawerFor(entry)
                2 -> startActivitySafely(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${entry.packageName}")
                    )
                )
                3 -> {
                    prefs.hideApp(entry.packageName)
                    refreshSuggestions()
                    onQueryChanged(binding.searchInput.text.toString())
                }
                4 -> startActivitySafely(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}"))
                )
            }
        }
    }

    /** "Add to a drawer…": appends the app to the chosen drawer's list. */
    private fun pickDrawerFor(entry: AppEntry) {
        val drawers = prefs.drawers()
        val items = drawers.map {
            AppPickerDialog.Item(
                it.name, androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_apps)
            )
        }
        AppPickerDialog.singleChoice(this, getString(R.string.menu_add_to_drawer), items, -1) { which ->
            val drawer = drawers[which]
            if (drawer.apps.isEmpty()) {
                Toast.makeText(this, R.string.drawer_drop_needs_list, Toast.LENGTH_LONG).show()
            } else {
                prefs.saveDrawer(
                    drawer.index, drawer.name, (drawer.apps - entry.componentKey) + entry.componentKey
                )
                refreshOpenDrawers()
            }
        }
    }

    // ---------------------------------------------------------- predictions

    // A deeper ranking than the visible trio: swiping the row pages through it.
    private var suggestionPool: List<AppEntry> = emptyList()
    private var suggestionPage = 0

    private fun refreshSuggestions() {
        if (!prefs.predictions) {
            // Static mode: the user's go-to apps, nothing learned or logged.
            val hidden = prefs.hiddenApps
            val favs = prefs.favorites
                .filter { it !in hidden }
                .mapNotNull { repo.byPackage(it) }
            suggestionPool = favs
            suggestionPage = 0
            applySuggestions(favs.take(3), animate = false)
            return
        }
        PredictionEngine.computeSuggestions(this, SUGGESTION_POOL_SIZE) { ranked, scores ->
            repo.usageBoost = scores
            suggestionPool = ranked.mapNotNull { repo.byPackage(it) }
            suggestionPage = 0
            applySuggestions(suggestionPool.take(3), animate = true)
        }
    }

    private fun suggestionViews() =
        listOf(binding.suggestLeft, binding.suggestMain, binding.suggestRight)

    /** Pages the visible trio through the deeper ranking (wraps around). */
    private fun cycleSuggestions(direction: Int) {
        val pages = (suggestionPool.size + 2) / 3
        if (pages <= 1) {
            // Nothing to page to; if a live lean had started, straighten back up.
            if (prefs.animations) {
                for (v in suggestionViews()) v.animate().rotationY(0f).setDuration(160).start()
            }
            return
        }
        haptic(binding.suggestionsBlock)
        suggestionPage = (suggestionPage + direction + pages) % pages
        val next = suggestionPool.drop(suggestionPage * 3).take(3)
        // Page direction is opposite the finger: the coin turns with the finger.
        if (prefs.animations) flipSuggestionsTo(next, -direction)
        else applySuggestions(next, animate = false)
    }

    /**
     * Coin-flip on the vertical axis toward [visualDir] (the finger's direction):
     * completes the turn from wherever the live lean left the trio, swaps it, and
     * rotates the new one back in.
     */
    private fun flipSuggestionsTo(entries: List<AppEntry>, visualDir: Int) {
        val views = suggestionViews()
        val camera = 9000 * resources.displayMetrics.density
        val out = 90f * visualDir
        for (v in views) {
            v.cameraDistance = camera
            v.animate().rotationY(out).setDuration(130)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .start()
        }
        binding.suggestMain.postDelayed({
            applySuggestions(entries, animate = false)
            for ((i, v) in views.withIndex()) {
                v.rotationY = -out
                v.animate().rotationY(0f).setDuration(190)
                    .setStartDelay(35L * i)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }, 140)
    }

    // ------------------------------------------------ suggestion swipe visuals

    // Live feedback while swiping the suggestions row: an accent glow rides under
    // the finger, blooming into sparkles as the swipe nears its trigger distance.
    private var suggSwipeActive = false
    private var suggSwipeArmed = false

    private fun suggTriggerPx(): Float = 56 * resources.displayMetrics.density

    private fun updateSuggestionSwipe(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY
        if (!suggSwipeActive) {
            val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
            if (abs(dx) < slop || abs(dx) < abs(dy) * 1.2f) return
            suggSwipeActive = true
            suggSwipeArmed = false
            binding.swipeGlow.setImageDrawable(glowDrawable(accentColor(), 48f))
            binding.swipeSparkle.setColor(accentColor())
            binding.swipeGlow.visibility = View.VISIBLE
        }
        val progress = (abs(dx) / suggTriggerPx()).coerceAtMost(1.3f)
        val glow = binding.swipeGlow
        val half = glow.layoutParams.width / 2f
        glow.x = event.x - half
        glow.y = event.y - half
        glow.alpha = 0.35f + 0.65f * progress.coerceAtMost(1f)
        val scale = 0.55f + 0.55f * progress
        glow.scaleX = scale
        glow.scaleY = scale
        if (prefs.animations) {
            // The coin flip starts with the finger: the trio leans further over as
            // the swipe builds, and the release animation completes the turn from
            // wherever the finger left it.
            val lean = LIVE_FLIP_DEG * progress.coerceAtMost(1f) * (if (dx < 0) -1 else 1)
            val camera = 9000 * resources.displayMetrics.density
            for (v in suggestionViews()) {
                v.cameraDistance = camera
                v.rotationY = lean
            }
            // Powder sparkles living around the finger, denser toward the trigger.
            binding.swipeSparkle.emit(event.x, event.y, progress.coerceAtMost(1f))
        }
        // A tick the moment the swipe is far enough to take.
        if (progress >= 1f && !suggSwipeArmed) {
            suggSwipeArmed = true
            haptic(binding.suggestionsBlock)
        } else if (progress < 1f) {
            suggSwipeArmed = false
        }
    }

    private fun finishSuggestionSwipe(dx: Float, x: Float, y: Float, confirmed: Boolean) {
        suggSwipeActive = false
        val taken = confirmed && abs(dx) >= suggTriggerPx()
        binding.swipeGlow.animate().alpha(0f).setDuration(160).withEndAction {
            binding.swipeGlow.visibility = View.GONE
        }.start()
        if (taken) {
            // Completion firework at the release point; leftover powder dies on its own.
            if (prefs.animations) binding.swipeSparkle.burst(x, y)
            cycleSuggestions(if (dx < 0) 1 else -1)
        } else if (prefs.animations) {
            // Cancelled: the trio leans back upright.
            for (v in suggestionViews()) v.animate().rotationY(0f).setDuration(160).start()
        }
    }

    private fun applySuggestions(entries: List<AppEntry>, animate: Boolean) {
        suggestions = listOf(entries.getOrNull(0), entries.getOrNull(1), entries.getOrNull(2))
        bindSuggestion(
            suggestions[0], binding.suggestMain,
            binding.suggestMainIcon, binding.suggestMainLabel, binding.suggestMainBadge
        )
        bindSuggestion(
            suggestions[1], binding.suggestLeft,
            binding.suggestLeftIcon, binding.suggestLeftLabel, binding.suggestLeftBadge
        )
        bindSuggestion(
            suggestions[2], binding.suggestRight,
            binding.suggestRightIcon, binding.suggestRightLabel, binding.suggestRightBadge
        )
        if (animate && prefs.animations) animateSuggestionsIn()
    }

    private fun bindSuggestion(
        entry: AppEntry?, container: View, icon: ImageView, label: TextView, badge: TextView,
    ) {
        container.rotationY = 0f // clear any leftover coin-flip lean
        if (entry == null) {
            container.visibility = View.INVISIBLE
            return
        }
        container.visibility = View.VISIBLE
        icon.setImageDrawable(repo.icon(entry))
        label.text = entry.label
        val count = badgeFor(entry.packageName)
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) badge.text = if (count > 99) "99+" else count.toString()
    }

    private fun animateSuggestionsIn() {
        for ((i, v) in listOf(
            binding.suggestMain, binding.suggestLeft, binding.suggestRight
        ).withIndex()) {
            if (v.visibility != View.VISIBLE) continue
            v.alpha = 0f
            v.translationY = 24f
            v.animate().alpha(1f).translationY(0f)
                .setDuration(220L)
                .setStartDelay(40L * i)
                .start()
        }
    }

    private fun onAppsChanged() {
        refreshSuggestions()
        updateNewAppSpot()
        if (binding.results.visibility == View.VISIBLE) {
            onQueryChanged(binding.searchInput.text.toString())
        }
    }

    // ------------------------------------------- notification badges & ticker

    /** Bubble count for a package; 0 when badges are off or access is missing. */
    private fun badgeFor(pkg: String): Int =
        if (prefs.notifBadges) NotifListener.count(pkg) else 0

    private var lastNotifPkgs: Set<String> = emptySet()

    /** Active-notification set changed: refresh every visible badge and the ticker. */
    private fun onNotificationsChanged() {
        // Which apps notify feeds the prediction ranking; recompute only when that
        // set changes (not on every count bump) to keep this cheap.
        val notifying = NotifListener.counts.filterValues { it > 0 }.keys
        if (notifying != lastNotifPkgs) {
            lastNotifPkgs = notifying
            if (prefs.predictions) refreshSuggestions()
        }
        // Suggestions: re-bind badges only, no re-prediction.
        for ((entry, badge) in listOf(
            suggestions.getOrNull(0) to binding.suggestMainBadge,
            suggestions.getOrNull(1) to binding.suggestLeftBadge,
            suggestions.getOrNull(2) to binding.suggestRightBadge,
        )) {
            if (entry == null) continue
            val count = badgeFor(entry.packageName)
            badge.visibility = if (count > 0) View.VISIBLE else View.GONE
            if (count > 0) badge.text = if (count > 99) "99+" else count.toString()
        }
        if (binding.results.visibility == View.VISIBLE) adapter.notifyDataSetChanged()
        binding.leftDrawer.refreshBadges()
        binding.rightDrawer.refreshBadges()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            startTicker()
        }
    }

    // The ticker shows one unread message at a time: fade in, hold, fade to the next.
    private val tickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var tickerIndex = 0
    private val tickerRunnable = object : Runnable {
        override fun run() {
            showNextTickerMessage()
            tickerHandler.postDelayed(this, prefs.tickerSeconds * 1000L)
        }
    }

    private fun startTicker() {
        tickerHandler.removeCallbacks(tickerRunnable)
        val active = prefs.messageTicker && NotifListener.messages.isNotEmpty()
        if (!active) {
            binding.ticker.visibility = View.GONE
            return
        }
        tickerIndex = 0
        tickerHandler.post(tickerRunnable)
    }

    private fun stopTicker() {
        tickerHandler.removeCallbacks(tickerRunnable)
    }

    private fun showNextTickerMessage() {
        val messages = NotifListener.messages
        if (messages.isEmpty()) {
            binding.ticker.visibility = View.GONE
            tickerHandler.removeCallbacks(tickerRunnable)
            return
        }
        val msg = messages[tickerIndex % messages.size]
        tickerIndex++
        binding.ticker.setOnClickListener {
            repo.byPackage(msg.pkg)?.let { entry -> launchApp(entry, binding.ticker) }
        }
        val bind = {
            val appLabel = repo.byPackage(msg.pkg)?.label ?: msg.pkg
            if (prefs.tickerTwoLines) {
                binding.tickerTitle.text = "$appLabel · ${msg.title}"
                binding.tickerText.text = msg.text
                binding.tickerText.visibility = View.VISIBLE
            } else {
                binding.tickerTitle.text = "$appLabel · ${msg.title} — ${msg.text}"
                binding.tickerText.visibility = View.GONE
            }
        }
        if (!prefs.animations) {
            bind()
            binding.ticker.alpha = 0.92f
            binding.ticker.visibility = View.VISIBLE
            return
        }
        binding.ticker.animate().alpha(0f).setDuration(250).withEndAction {
            bind()
            binding.ticker.translationY = 6 * resources.displayMetrics.density
            binding.ticker.visibility = View.VISIBLE
            binding.ticker.animate().alpha(0.92f).translationY(0f).setDuration(350).start()
        }.start()
    }

    // ------------------------------------------------------------- updates

    private fun checkForUpdate() {
        if (ioExecutor.isShutdown) return
        ioExecutor.execute {
            val version = try {
                UpdateManager.maybeDailyCheck(this)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                pendingUpdateVersion = version
                if (version != null) {
                    binding.updatePill.text = getString(R.string.update_pill, version)
                    binding.updatePill.visibility = View.VISIBLE
                } else {
                    binding.updatePill.visibility = View.GONE
                }
            }
        }
    }

    // ---------------------------------------------------------- status line

    private var statusReceiver: android.content.BroadcastReceiver? = null

    private fun setupStatusLine() {
        unregisterStatusReceiver()
        val enabled = prefs.statusTokens
        val statusOn = prefs.showStatusLine && enabled.isNotEmpty()
        if (!statusOn) {
            binding.statusLine.visibility = View.GONE
        } else {
            binding.statusLine.setTextColor(ColorUtils.setAlphaComponent(accentColor(), 0xC0))
            refreshStatusLine()
        }
        // The minute tick drives both the status line and the agenda countdown, so
        // it's registered when either is live. Only subscribe to the (frequent)
        // battery broadcasts when the battery token is actually shown.
        if (!statusOn && !prefs.agendaEnabled) return
        statusReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) {
                if (statusOn) refreshStatusLine()
                if (i?.action == Intent.ACTION_TIME_TICK) renderAgenda()
            }
        }
        val filter = android.content.IntentFilter(Intent.ACTION_TIME_TICK)
        if (statusOn && StatusLine.BATTERY in enabled) {
            filter.addAction(Intent.ACTION_BATTERY_CHANGED)
            filter.addAction(Intent.ACTION_POWER_CONNECTED)
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(statusReceiver, filter)
    }

    private fun unregisterStatusReceiver() {
        statusReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // already unregistered
            }
        }
        statusReceiver = null
    }

    private fun refreshStatusLine() {
        if (!prefs.showStatusLine) return
        val enabled = prefs.statusTokens
        if (enabled.isEmpty()) {
            binding.statusLine.visibility = View.GONE
            return
        }
        if (ioExecutor.isShutdown) return
        // This runs every minute while the launcher is in front, and each field is a
        // binder or disk read — gather them off-thread, render back on main.
        ioExecutor.execute {
            // Compute only the fields that are actually shown.
            var percent = 0
            var charging = false
            if (StatusLine.BATTERY in enabled) {
                val battery = registerReceiver(
                    null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
                val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
            val net = if (StatusLine.NETWORK in enabled) currentNet() else StatusLine.Net.OFFLINE
            val alarm = if (StatusLine.ALARM in enabled) nextAlarmText() else null
            val storage = if (StatusLine.STORAGE in enabled) freeStorageGb() else 0.0
            val dbBytes = if (StatusLine.DB in enabled) {
                try {
                    getDatabasePath("usage.db").length()
                } catch (e: Exception) {
                    0L
                }
            } else 0L

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                fun render(launches: Int) {
                    binding.statusLine.text = clickableStatusText(
                        StatusLine.segments(
                            enabled,
                            StatusLine.Values(
                                percent, charging, net, alarm, launches, storage, dbBytes
                            )
                        ),
                        net
                    )
                    binding.statusLine.movementMethod =
                        android.text.method.LinkMovementMethod.getInstance()
                    binding.statusLine.visibility = View.VISIBLE
                }
                if (StatusLine.LAUNCHES in enabled) PredictionEngine.launchesToday(this) { render(it) }
                else render(0)
            }
        }
    }

    /** Each token becomes tappable, opening its related app or settings screen. */
    private fun clickableStatusText(
        segments: List<Pair<String, String>>, net: StatusLine.Net,
    ): CharSequence {
        val text = android.text.SpannableStringBuilder("› ")
        for ((i, segment) in segments.withIndex()) {
            if (i > 0) text.append("  ·  ")
            val start = text.length
            text.append(segment.second)
            val span = object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    haptic(binding.statusLine)
                    when (segment.first) {
                        StatusLine.BATTERY ->
                            openSettingsAction(Intent.ACTION_POWER_USAGE_SUMMARY)
                        StatusLine.NETWORK -> openSettingsAction(
                            if (net == StatusLine.Net.WIFI) Settings.ACTION_WIFI_SETTINGS
                            else Settings.ACTION_WIRELESS_SETTINGS
                        )
                        StatusLine.ALARM ->
                            startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                        StatusLine.STORAGE ->
                            openSettingsAction(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                        // Launch count and DB size both live in the insights screen.
                        else -> startActivity(Intent(this@MainActivity, InsightsActivity::class.java))
                    }
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    // Keep the terminal look: no underline, no link color.
                }
            }
            text.setSpan(span, start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun freeStorageGb(): Double = try {
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        stat.availableBytes.toDouble() / 1_000_000_000.0
    } catch (e: Exception) {
        0.0
    }

    private fun currentNet(): StatusLine.Net {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
            ?: return StatusLine.Net.OFFLINE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return StatusLine.Net.OFFLINE
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> StatusLine.Net.WIFI
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> StatusLine.Net.CELLULAR
            else -> StatusLine.Net.OFFLINE
        }
    }

    private fun nextAlarmText(): String? {
        // Two flawed sources, reconciled: AlarmClockInfo.triggerTime is authoritative
        // and timezone-safe but some clock apps schedule it earlier than the alarm
        // they display (smart-wake / pre-wake offsets); NEXT_ALARM_FORMATTED matches
        // the Clock app but is a deprecated string some ROMs leave stale or format
        // oddly. Trusting it verbatim showed plainly wrong times, so it is only
        // believed when it lands within the pre-wake window after the trigger.
        // (Raw values from both sources are visible in the Insights screen.)
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return null
        val info = am.nextAlarmClock ?: return null // framework says no alarm: show none
        val formatted = try {
            @Suppress("DEPRECATION")
            Settings.System.getString(contentResolver, Settings.System.NEXT_ALARM_FORMATTED)
        } catch (e: Exception) {
            null
        }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = info.triggerTime }
        val triggerMinutes =
            cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val display =
            StatusLine.reconcileAlarmMinutes(StatusLine.parseTimeOfDay(formatted), triggerMinutes)
        if (display != triggerMinutes) {
            // Pre-wake case: re-time the trigger instant to the Clock app's hour.
            cal.set(java.util.Calendar.HOUR_OF_DAY, display / 60)
            cal.set(java.util.Calendar.MINUTE, display % 60)
        }
        return android.text.format.DateFormat.getTimeFormat(this).format(cal.time)
    }

    // --------------------------------------------------------------- agenda

    private var agendaEvents: List<Agenda.EventInstance> = emptyList()
    private var agendaObserver: android.database.ContentObserver? = null
    private val agendaHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val agendaReload = Runnable { refreshAgenda() }
    // "Show only on gesture" mode: the stream stays hidden until summoned.
    private var agendaShownByGesture = false

    /** The AGENDA gesture: summon/dismiss in gesture mode, refresh in always-on. */
    private fun toggleAgenda() {
        if (!prefs.agendaEnabled || !CalendarFeed.hasPermission(this)) {
            // Not set up yet: land on the agenda settings rather than a dead swipe.
            SettingsActivity.open(this, SettingsActivity.SCREEN_AGENDA)
            return
        }
        if (prefs.agendaOnGesture) {
            agendaShownByGesture = !agendaShownByGesture
            if (agendaShownByGesture) refreshAgenda() else renderAgenda()
        } else {
            refreshAgenda()
        }
    }

    private fun setupAgenda() {
        binding.agenda.haptic = { v -> haptic(v) }
        binding.agenda.onLocationClick = { location ->
            // Straight into navigation: any maps app resolves the geo query.
            startActivitySafely(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(location)))
            )
        }
        binding.agenda.onOpenEvent = { event ->
            startActivitySafely(
                Intent(Intent.ACTION_VIEW)
                    .setData(
                        android.content.ContentUris.withAppendedId(
                            android.provider.CalendarContract.Events.CONTENT_URI, event.eventId
                        )
                    )
                    .putExtra(
                        android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin
                    )
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            )
        }
        // The stream forwards what it doesn't use, like the drawers do.
        binding.agenda.onSwipe = { dx, dy, dtMs, fingers ->
            handleSwipe(dx, dy, dtMs, fingers.coerceIn(1, 2), fromDrawer = true)
        }
    }

    private fun refreshAgenda() {
        if (!prefs.agendaEnabled || !CalendarFeed.hasPermission(this)) {
            binding.agenda.visibility = View.GONE
            return
        }
        CalendarFeed.load(this, prefs.agendaDays, prefs.agendaExcludedCalendars) { events ->
            if (isDestroyed) return@load
            agendaEvents = events
            renderAgenda()
        }
    }

    /** Re-derives rows from cached events — cheap, also runs on the minute tick. */
    private fun renderAgenda() {
        if (!prefs.agendaEnabled || !CalendarFeed.hasPermission(this)) {
            binding.agenda.visibility = View.GONE
            return
        }
        val source =
            if (prefs.agendaShowAllDay) agendaEvents else agendaEvents.filter { !it.allDay }
        val rows = Agenda.rows(source, System.currentTimeMillis())
        binding.agenda.tapOpensApp = prefs.agendaTapOpensApp
        binding.agenda.configure(
            prefs.agendaTextSizeSp, prefs.agendaLines, prefs.agendaShowCountdown
        )
        binding.agenda.setAccent(accentColor())
        binding.agenda.submit(rows)
        val hidden = rows.isEmpty() ||
            binding.results.visibility == View.VISIBLE ||
            anyDrawerOpen || // the wheel needs the width; the stream yields
            (prefs.agendaOnGesture && !agendaShownByGesture)
        binding.agenda.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun registerAgendaObserver() {
        if (!prefs.agendaEnabled || !CalendarFeed.hasPermission(this)) return
        val observer = object : android.database.ContentObserver(agendaHandler) {
            override fun onChange(selfChange: Boolean) {
                // Provider syncs fire bursts of change events; coalesce to one reload.
                agendaHandler.removeCallbacks(agendaReload)
                agendaHandler.postDelayed(agendaReload, 500)
            }
        }
        contentResolver.registerContentObserver(
            android.provider.CalendarContract.CONTENT_URI, true, observer
        )
        agendaObserver = observer
    }

    private fun unregisterAgendaObserver() {
        agendaHandler.removeCallbacks(agendaReload)
        agendaObserver?.let { contentResolver.unregisterContentObserver(it) }
        agendaObserver = null
    }

    private fun maybeRequestCalendarPermission() {
        if (!prefs.agendaEnabled || prefs.calendarPermissionAsked) return
        if (CalendarFeed.hasPermission(this)) return
        prefs.calendarPermissionAsked = true
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
    }

    // ------------------------------------------------------ new-app spotlight

    // A freshly installed app shines on one edge for a configurable while; several
    // new apps rotate through the same spot.
    private val spotHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var spotIndex = 0
    private var spotPulse: android.animation.ObjectAnimator? = null
    private val spotRunnable = object : Runnable {
        override fun run() {
            showNextSpotApp()
            spotHandler.postDelayed(this, SPOT_ROTATE_MS)
        }
    }

    private fun updateNewAppSpot() {
        spotHandler.removeCallbacks(spotRunnable)
        stopSpotPulse()
        val left = prefs.newAppSide == "left"
        val drawerOnSide =
            if (left) binding.leftDrawer.isVisibleAtAll else binding.rightDrawer.isVisibleAtAll
        val apps = if (prefs.newAppSpotEnabled) {
            prefs.newApps().mapNotNull { repo.byPackage(it) }
        } else {
            emptyList()
        }
        if (apps.isEmpty() || drawerOnSide || binding.results.visibility == View.VISIBLE) {
            binding.newAppSpot.visibility = View.GONE
            return
        }
        val lp = binding.newAppSpot.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.gravity = android.view.Gravity.TOP or
            (if (left) android.view.Gravity.START else android.view.Gravity.END)
        lp.topMargin = (binding.root.height * 0.17f).toInt()
            .coerceAtLeast((110 * resources.displayMetrics.density).toInt())
        binding.newAppSpot.layoutParams = lp
        binding.newAppGlow.setImageDrawable(glowDrawable(accentColor(), 42f))
        binding.newAppStar.setTextColor(accentColor())
        binding.newAppSpot.visibility = View.VISIBLE
        spotIndex = 0
        spotHandler.post(spotRunnable)
        startSpotPulse()
    }

    private fun showNextSpotApp() {
        val apps = prefs.newApps().mapNotNull { repo.byPackage(it) }
        if (apps.isEmpty()) {
            binding.newAppSpot.visibility = View.GONE
            spotHandler.removeCallbacks(spotRunnable)
            stopSpotPulse()
            return
        }
        val entry = apps[spotIndex % apps.size]
        spotIndex++
        val bindIcon = { binding.newAppIcon.setImageDrawable(repo.icon(entry)) }
        if (prefs.animations && apps.size > 1) {
            binding.newAppIcon.animate().alpha(0f).setDuration(220).withEndAction {
                bindIcon()
                binding.newAppIcon.animate().alpha(1f).setDuration(220).start()
            }.start()
        } else {
            bindIcon()
        }
        binding.newAppSpot.setOnClickListener {
            prefs.removeNewApp(entry.packageName)
            launchApp(entry, binding.newAppIcon)
            updateNewAppSpot()
        }
        binding.newAppSpot.setOnLongClickListener {
            showAppMenu(entry, binding.newAppIcon)
            true
        }
    }

    /** The "shine": a slow glow breath. Runs only while the spot is on screen. */
    private fun startSpotPulse() {
        if (!prefs.animations) return
        spotPulse = android.animation.ObjectAnimator.ofFloat(
            binding.newAppGlow, View.ALPHA, 0.55f, 1f
        ).apply {
            duration = 1600
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopSpotPulse() {
        spotPulse?.cancel()
        spotPulse = null
        binding.newAppGlow.alpha = 1f
    }

    private fun stopNewAppSpot() {
        spotHandler.removeCallbacks(spotRunnable)
        stopSpotPulse()
    }

    // ----------------------------------------------------------- appearance

    private fun applyAppearance() {
        binding.scrim.alpha = prefs.dim / 100f
        val visible = if (prefs.showClock) View.VISIBLE else View.GONE
        binding.clock.visibility = visible
        binding.date.visibility = visible

        val accent = accentColor()
        binding.mainGlow.setImageDrawable(glowDrawable(accent, 60f))
        binding.updatePill.setTextColor(accent)
        binding.starterPill.setTextColor(accent)
        updateModeUi()
    }

    /** Accent radial glow used behind the main suggestion and the new-app spotlight. */
    private fun glowDrawable(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, radiusDp, resources.displayMetrics
            )
            colors = intArrayOf(
                ColorUtils.setAlphaComponent(color, 0x5E),
                ColorUtils.setAlphaComponent(color, 0x00),
            )
        }

    private fun accentColor(): Int = when (prefs.accent) {
        "purple" -> getColor(R.color.accent_purple)
        "green" -> getColor(R.color.accent_green)
        "amber" -> getColor(R.color.accent_amber)
        "pink" -> getColor(R.color.accent_pink)
        else -> getColor(R.color.accent_cyan)
    }

    // -------------------------------------------------------------- helpers

    private fun focusSearch(show: Boolean) {
        binding.searchInput.requestFocus()
        if (show) {
            binding.searchInput.post {
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    private fun haptic(view: View) {
        if (prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun maybeRequestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        if (!prefs.predictions || !prefs.btSignal || prefs.btPermissionAsked) return
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        prefs.btPermissionAsked = true
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    private companion object {
        const val SUGGESTION_POOL_SIZE = 12
        const val SPOT_ROTATE_MS = 4500L
        // Max live lean of the trio while the finger drags; the release animation
        // finishes the coin turn from there to 90°.
        const val LIVE_FLIP_DEG = 55f
    }
}
