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
import android.widget.PopupMenu
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
import fr.arichard.lastlauncher.command.CommandProcessor
import fr.arichard.lastlauncher.command.SearchMode
import fr.arichard.lastlauncher.databinding.ActivityMainBinding
import fr.arichard.lastlauncher.gesture.GestureAction
import fr.arichard.lastlauncher.gesture.GestureBinding
import fr.arichard.lastlauncher.lock.LockService
import fr.arichard.lastlauncher.predict.PredictionEngine
import fr.arichard.lastlauncher.settings.Prefs
import fr.arichard.lastlauncher.settings.SettingsActivity
import fr.arichard.lastlauncher.ui.AppAdapter
import fr.arichard.lastlauncher.ui.AppPickerDialog
import fr.arichard.lastlauncher.ui.StatusLine
import fr.arichard.lastlauncher.update.UpdateManager
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
            pendingUpdateVersion?.let { v -> UpdateManager.install(this, v) }
        }
        binding.clock.setOnClickListener { launchClockTarget() }
        binding.starterPill.setOnClickListener { showStarterPicker() }
        repo.addListener(repoListener)
    }

    override fun onDestroy() {
        repo.removeListener(repoListener)
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
        checkForUpdate()
        setupStatusLine()
    }

    override fun onPause() {
        super.onPause()
        stopHints()
        unregisterStatusReceiver()
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
        if (drawerOpen) closeDrawer(animate = true)
        else if (allAppsOpen || binding.searchInput.text.isNotEmpty()) resetToHome()
    }

    // ---------------------------------------------------------------- setup

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            binding.content.setPadding(
                binding.content.paddingLeft, bars.top,
                binding.content.paddingRight, bars.bottom
            )
            insets
        }
    }

    // Manual swipe tracking so we know the finger count, which GestureDetector hides.
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 1

    private fun setupGestures() {
        // Double-tap and long-press still come from GestureDetector; swipes are tracked
        // manually below to distinguish one- from two-finger horizontal gestures.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (prefs.doubleTapLock) lockScreen()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                haptic(binding.root)
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        })
        binding.root.isClickable = true
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val edgeZone = 40 * resources.displayMetrics.density
        binding.root.setOnTouchListener { v, event ->
            // ACTION_DOWN must be handled before the detector sees it: a pending drawer pull
            // disables long-press so a hesitant edge grab doesn't open Settings instead.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                maxPointers = 1
                drawerDragging = false
                drawerCandidateSide = drawerEdgeCandidate(event.x, v.width, edgeZone)
                detector.setIsLongpressEnabled(drawerCandidateSide == 0)
                if (drawerCandidateSide != 0) {
                    velocityTracker = android.view.VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                }
            }
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, event.pointerCount)
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
                        settleDrawer(vx)
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
        if (drawerOpen) return 0
        if (x < edgeZone && edgeBoundToDrawer(Prefs.KEY_GESTURE_LR_1, Prefs.KEY_GESTURE_LR_2)) return -1
        if (x > width - edgeZone && edgeBoundToDrawer(Prefs.KEY_GESTURE_RL_1, Prefs.KEY_GESTURE_RL_2)) return 1
        return 0
    }

    private fun edgeBoundToDrawer(vararg keys: String): Boolean = keys.any {
        GestureBinding.decode(prefs.gestureBinding(it)).action == GestureAction.APP_DRAWER
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
            beginDrawerDrag(drawerCandidateSide)
        }
        val progress = if (drawerSide < 0) dx / drawerOffset() else -dx / drawerOffset()
        setDrawerProgress(progress)
    }

    private fun handleSwipe(dx: Float, dy: Float, dtMs: Long) {
        if (dtMs > 700) return
        val density = resources.displayMetrics.density
        val minDist = 90 * density
        val fingers = maxPointers.coerceIn(1, 2)
        if (abs(dx) > abs(dy) && abs(dx) > minDist) {
            // Horizontal: direction picks the edge set, finger count picks the slot.
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
            if (dy < 0) openAllApps()
            else if (prefs.swipeDownNotifications) LockService.openNotificationShade(this)
        }
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
            GestureAction.APP_DRAWER -> openDrawer(drawerSide, animate = true)
            GestureAction.SEARCH -> focusSearch(show = true)
            GestureAction.CAMERA -> startActivitySafely(
                Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            )
            GestureAction.DIALER -> startActivitySafely(Intent(Intent.ACTION_DIAL))
            GestureAction.OPEN_APP ->
                bindingSpec.appKey?.let { key ->
                    repo.byComponentKey(key)?.let { launchApp(it, binding.root) }
                }
        }
    }

    // ------------------------------------------------------------- drawer

    private lateinit var drawerAdapter: AppAdapter
    private var drawerOpen = false
    private var drawerSide = -1          // -1 anchored left, +1 anchored right
    private var drawerCandidateSide = 0  // edge the current touch could pull the drawer from
    private var drawerDragging = false
    private var velocityTracker: android.view.VelocityTracker? = null

    private fun setupDrawer() {
        drawerAdapter = AppAdapter(
            repo,
            onAppClick = { entry, view -> closeDrawer(animate = false); launchApp(entry, view) },
            onAppLongClick = { entry, view -> showAppMenu(entry, view) },
            onCommand = {},
        )
        binding.drawerList.layoutManager = LinearLayoutManager(this)
        binding.drawerList.adapter = drawerAdapter
        binding.drawerList.itemAnimator = null
        binding.drawerScrim.setOnClickListener { closeDrawer(animate = true) }
    }

    private fun drawerContents(): List<AppEntry> {
        val keys = prefs.drawerApps
        return if (keys.isEmpty()) repo.visibleApps(prefs.hiddenApps)
        else keys.mapNotNull { repo.byComponentKey(it) }
    }

    /** Anchors the panel to the given [side] (-1 left, +1 right) via its layout gravity. */
    private fun placeDrawer(side: Int) {
        drawerSide = side
        val lp = binding.drawerList.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.gravity = if (side < 0) android.view.Gravity.START else android.view.Gravity.END
        binding.drawerList.layoutParams = lp
    }

    /** Off-screen distance for the closed panel: measured width + its outer margin. */
    private fun drawerOffset(): Float {
        val lp = binding.drawerList.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val margin = if (drawerSide < 0) lp.leftMargin else lp.rightMargin
        val w = binding.drawerList.width
        return if (w > 0) (w + margin).toFloat() else 320 * resources.displayMetrics.density
    }

    private fun closedTranslation(): Float =
        if (drawerSide < 0) -drawerOffset() else drawerOffset()

    private fun setDrawerProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        binding.drawerList.visibility = View.VISIBLE
        binding.drawerScrim.visibility = View.VISIBLE
        binding.drawerList.translationX = closedTranslation() * (1f - p)
        binding.drawerScrim.alpha = 0.55f * p
    }

    private fun beginDrawerDrag(side: Int) {
        placeDrawer(side)
        drawerAdapter.submit(drawerContents())
        setDrawerProgress(0f)
    }

    private fun openDrawer(side: Int, animate: Boolean) {
        if (!::drawerAdapter.isInitialized) return
        // Already visible (settling from a drag) → animate on from the finger position,
        // don't snap back to fully closed first.
        val wasVisible = binding.drawerList.visibility == View.VISIBLE
        placeDrawer(side)
        drawerAdapter.submit(drawerContents())
        binding.drawerList.visibility = View.VISIBLE
        binding.drawerScrim.visibility = View.VISIBLE
        drawerOpen = true
        if (animate && prefs.animations) {
            if (!wasVisible) binding.drawerList.translationX = closedTranslation()
            binding.drawerList.animate().translationX(0f).setDuration(220).start()
            binding.drawerScrim.animate().alpha(0.55f).setDuration(220).start()
        } else {
            setDrawerProgress(1f)
        }
    }

    private fun closeDrawer(animate: Boolean) {
        if (!::drawerAdapter.isInitialized) return
        drawerOpen = false
        drawerDragging = false
        val hide = {
            binding.drawerList.visibility = View.GONE
            binding.drawerScrim.visibility = View.GONE
        }
        if (animate && prefs.animations && binding.drawerList.visibility == View.VISIBLE) {
            binding.drawerList.animate().translationX(closedTranslation()).setDuration(200)
                .withEndAction(hide).start()
            binding.drawerScrim.animate().alpha(0f).setDuration(200).start()
        } else {
            hide()
        }
    }

    /**
     * On release, settle open or closed. A decisive flick wins by its direction (so a
     * fast short pull still opens); otherwise fall back to how far it was dragged.
     */
    private fun settleDrawer(velocityX: Float) {
        val fast = abs(velocityX) > 800 * resources.displayMetrics.density
        val opening = if (drawerSide < 0) velocityX > 0 else velocityX < 0
        val closed = closedTranslation()
        val progress = 1f - (binding.drawerList.translationX / closed).coerceIn(0f, 1f)
        val open = if (fast) opening else progress > 0.4f
        if (open) openDrawer(drawerSide, animate = true)
        else closeDrawer(animate = true)
    }

    private fun setupSearch() {
        adapter = AppAdapter(
            repo,
            onAppClick = { entry, view -> launchApp(entry, view) },
            onAppLongClick = { entry, view -> showAppMenu(entry, view) },
            onCommand = { command -> runCommand(command) },
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
        // Left button is the mode wheel: tap to cycle, long-press for the assistant.
        binding.modeBtn.setOnClickListener { cycleMode() }
        binding.modeBtn.setOnLongClickListener { launchAssistant(); true }
        binding.allAppsBtn.setOnClickListener { if (allAppsOpen) resetToHome() else openAllApps() }
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
        binding.suggestMain.setOnLongClickListener { v ->
            suggestionAt(0)?.let { showAppMenu(it, v) }; true
        }
        binding.suggestLeft.setOnLongClickListener { v ->
            suggestionAt(1)?.let { showAppMenu(it, v) }; true
        }
        binding.suggestRight.setOnLongClickListener { v ->
            suggestionAt(2)?.let { showAppMenu(it, v) }; true
        }
    }

    private var suggestions: List<AppEntry?> = listOf(null, null, null)

    private fun suggestionAt(rank: Int): AppEntry? = suggestions.getOrNull(rank)

    // ------------------------------------------------------------- behavior

    private fun onQueryChanged(raw: String) {
        val query = raw.trim()
        val mode = searchMode
        // Empty with no active view is the home screen — suggestions + hints, mode-independent.
        if (query.isEmpty() && !allAppsOpen) {
            binding.results.visibility = View.GONE
            binding.suggestionsBlock.visibility = View.VISIBLE
            startHints()
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
        binding.results.scrollToPosition(0)
        stopHints()
        binding.hintLeft.visibility = View.GONE
        binding.hintRight.visibility = View.GONE
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
        onQueryChanged(binding.searchInput.text.toString())
        focusSearch(show = true)
    }

    private fun resetToHome() {
        allAppsOpen = false
        closeDrawer(animate = false)
        if (binding.searchInput.text.isNotEmpty()) {
            binding.searchInput.setText("")
        }
        binding.results.visibility = View.GONE
        binding.suggestionsBlock.visibility = View.VISIBLE
        startHints()
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

    /**
     * One-time cold-start helper: lets the user pick go-to apps that fill the
     * suggestion slots until the prediction engine has learned enough. Pre-checks
     * sensible guesses (phone, SMS, browser, camera).
     */
    private fun showStarterPicker() {
        val apps = repo.visibleApps(prefs.hiddenApps)
        if (apps.isEmpty()) return
        val current = prefs.favorites.ifEmpty { PredictionEngine.defaultPicks(this) }
        val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
        val checked = BooleanArray(apps.size) { apps[it].packageName in current }
        AppPickerDialog.multiChoice(this, getString(R.string.starter_dialog_title), items, checked) {
            prefs.favorites = apps.indices.filter { checked[it] }.map { apps[it].packageName }
            prefs.onboardingDone = true
            updateStarterPill()
            refreshSuggestions()
        }
    }

    // -------------------------------------------------------- gesture hints

    // Each edge cycles between its one- and two-finger bindings.
    private val hintHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hintStep = 0
    private val hintRunnable = object : Runnable {
        override fun run() {
            renderHints(hintStep)
            hintStep = hintStep xor 1
            hintHandler.postDelayed(this, 2600)
        }
    }

    private fun startHints() {
        hintHandler.removeCallbacks(hintRunnable)
        val show = prefs.showGestureHints &&
            binding.results.visibility != View.VISIBLE
        if (!show) {
            binding.hintLeft.visibility = View.GONE
            binding.hintRight.visibility = View.GONE
            return
        }
        hintStep = 0
        hintHandler.post(hintRunnable)
        startNudges()
    }

    private fun stopHints() {
        hintHandler.removeCallbacks(hintRunnable)
        stopNudges()
    }

    // A gentle, endless slide in each edge's swipe direction — the hint "leans" the
    // way you should swipe. Independent of the fade that plays on a text change.
    private var leftNudge: android.animation.ObjectAnimator? = null
    private var rightNudge: android.animation.ObjectAnimator? = null

    private fun startNudges() {
        stopNudges()
        if (!prefs.animations) return
        val amp = 7 * resources.displayMetrics.density
        leftNudge = nudge(binding.hintLeft, amp)
        rightNudge = nudge(binding.hintRight, -amp)
    }

    private fun nudge(view: View, dx: Float): android.animation.ObjectAnimator =
        android.animation.ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, dx).apply {
            duration = 900
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }

    private fun stopNudges() {
        leftNudge?.cancel()
        rightNudge?.cancel()
        leftNudge = null
        rightNudge = null
        binding.hintLeft.translationX = 0f
        binding.hintRight.translationX = 0f
    }

    /** Renders one edge frame: [step] 0 shows the one-finger binding, 1 the two-finger. */
    private fun renderHints(step: Int) {
        val accent = accentColor()
        val leftKey = if (step == 0) Prefs.KEY_GESTURE_LR_1 else Prefs.KEY_GESTURE_LR_2
        val rightKey = if (step == 0) Prefs.KEY_GESTURE_RL_1 else Prefs.KEY_GESTURE_RL_2
        val arrows = if (step == 0) 1 else 2
        crossfadeHint(binding.hintLeft, leftEdgeText(leftKey, arrows), accent)
        crossfadeHint(binding.hintRight, rightEdgeText(rightKey, arrows), accent)
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
        if (spec.action == GestureAction.NONE) return null
        if (spec.action == GestureAction.OPEN_APP) {
            val app = spec.appKey?.let { key -> repo.apps.firstOrNull { it.componentKey == key } }
                ?: return null
            return app.label
        }
        return getString(spec.action.labelRes)
    }

    private fun crossfadeHint(view: TextView, text: String?, accent: Int) {
        if (text == null) {
            view.visibility = View.GONE
            return
        }
        view.setTextColor(ColorUtils.setAlphaComponent(accent, 0xB0))
        if (!prefs.animations) {
            view.text = text
            view.visibility = View.VISIBLE
            view.alpha = 0.85f
            return
        }
        view.animate().alpha(0f).setDuration(200).withEndAction {
            view.text = text
            view.visibility = View.VISIBLE
            view.animate().alpha(0.85f).setDuration(200).start()
        }.start()
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
        val menu = PopupMenu(this, anchor)
        menu.menu.add(R.string.menu_app_info).setOnMenuItemClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${entry.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }
        menu.menu.add(R.string.menu_hide).setOnMenuItemClickListener {
            prefs.hideApp(entry.packageName)
            refreshSuggestions()
            onQueryChanged(binding.searchInput.text.toString())
            true
        }
        menu.menu.add(R.string.menu_uninstall).setOnMenuItemClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // system apps can refuse
            }
            true
        }
        menu.show()
    }

    // ---------------------------------------------------------- predictions

    private fun refreshSuggestions() {
        if (!prefs.predictions) {
            // Static mode: the user's go-to apps, nothing learned or logged.
            val hidden = prefs.hiddenApps
            val favs = prefs.favorites
                .filter { it !in hidden }
                .mapNotNull { repo.byPackage(it) }
            applySuggestions(favs, animate = false)
            return
        }
        PredictionEngine.computeSuggestions(this, 3) { ranked, scores ->
            repo.usageBoost = scores
            applySuggestions(ranked.mapNotNull { repo.byPackage(it) }, animate = true)
        }
    }

    private fun applySuggestions(entries: List<AppEntry>, animate: Boolean) {
        suggestions = listOf(entries.getOrNull(0), entries.getOrNull(1), entries.getOrNull(2))
        bindSuggestion(
            suggestions[0], binding.suggestMain,
            binding.suggestMainIcon, binding.suggestMainLabel
        )
        bindSuggestion(
            suggestions[1], binding.suggestLeft,
            binding.suggestLeftIcon, binding.suggestLeftLabel
        )
        bindSuggestion(
            suggestions[2], binding.suggestRight,
            binding.suggestRightIcon, binding.suggestRightLabel
        )
        if (animate && prefs.animations) animateSuggestionsIn()
    }

    private fun bindSuggestion(
        entry: AppEntry?, container: View, icon: ImageView, label: TextView,
    ) {
        if (entry == null) {
            container.visibility = View.INVISIBLE
            return
        }
        container.visibility = View.VISIBLE
        icon.setImageDrawable(repo.icon(entry))
        label.text = entry.label
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
        if (binding.results.visibility == View.VISIBLE) {
            onQueryChanged(binding.searchInput.text.toString())
        }
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
        if (!prefs.showStatusLine) {
            binding.statusLine.visibility = View.GONE
            return
        }
        val enabled = prefs.statusTokens
        if (enabled.isEmpty()) {
            binding.statusLine.visibility = View.GONE
            return
        }
        binding.statusLine.setTextColor(ColorUtils.setAlphaComponent(accentColor(), 0xC0))
        refreshStatusLine()
        // A minute tick covers alarm/launches/network; only subscribe to the (frequent)
        // battery broadcasts when the battery token is actually shown.
        statusReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) = refreshStatusLine()
        }
        val filter = android.content.IntentFilter(Intent.ACTION_TIME_TICK)
        if (StatusLine.BATTERY in enabled) {
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

        fun render(launches: Int) {
            binding.statusLine.text = StatusLine.build(
                enabled,
                StatusLine.Values(percent, charging, net, alarm, launches, storage)
            )
            binding.statusLine.visibility = View.VISIBLE
        }
        if (StatusLine.LAUNCHES in enabled) PredictionEngine.launchesToday(this) { render(it) }
        else render(0)
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
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return null
        val info = am.nextAlarmClock ?: return null
        return android.text.format.DateFormat.getTimeFormat(this).format(info.triggerTime)
    }

    // ----------------------------------------------------------- appearance

    private fun applyAppearance() {
        binding.scrim.alpha = prefs.dim / 100f
        val visible = if (prefs.showClock) View.VISIBLE else View.GONE
        binding.clock.visibility = visible
        binding.date.visibility = visible

        val accent = accentColor()
        binding.mainGlow.setImageDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics
            )
            colors = intArrayOf(
                ColorUtils.setAlphaComponent(accent, 0x5E),
                ColorUtils.setAlphaComponent(accent, 0x00),
            )
        })
        binding.updatePill.setTextColor(accent)
        binding.starterPill.setTextColor(accent)
        updateModeUi()
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
}
