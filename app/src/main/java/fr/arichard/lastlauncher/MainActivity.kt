package fr.arichard.lastlauncher

import android.Manifest
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ActivityNotFoundException
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
        // A launcher never finishes; back just collapses search.
        if (allAppsOpen || binding.searchInput.text.isNotEmpty()) resetToHome()
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
        binding.root.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    maxPointers = 1
                }
                MotionEvent.ACTION_POINTER_DOWN ->
                    maxPointers = maxOf(maxPointers, event.pointerCount)
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    handleSwipe(event.x - downX, event.y - downY, event.eventTime - downTime)
                }
            }
            true
        }
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
            runGesture(GestureBinding.decode(prefs.gestureBinding(key)))
        } else if (abs(dy) > abs(dx) && abs(dy) > minDist) {
            // Vertical stays fixed: up = all apps, down = notifications.
            if (dy < 0) openAllApps()
            else if (prefs.swipeDownNotifications) LockService.openNotificationShade(this)
        }
    }

    private fun runGesture(bindingSpec: GestureBinding) {
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
            GestureAction.SEARCH -> focusSearch(show = true)
            GestureAction.CAMERA -> startActivitySafely(
                Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            )
            GestureAction.DIALER -> startActivitySafely(Intent(Intent.ACTION_DIAL))
            GestureAction.OPEN_APP ->
                bindingSpec.appKey?.let { key ->
                    repo.apps.firstOrNull { it.componentKey == key }?.let { launchApp(it, binding.root) }
                }
        }
    }

    private fun setupSearch() {
        adapter = AppAdapter(
            repo,
            onAppClick = { entry, view -> launchApp(entry, view) },
            onAppLongClick = { entry, view -> showAppMenu(entry, view) },
            onCommand = { command -> runCommand(command) },
            onWebSearch = { query -> webSearch(query) },
        )
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
                when (val row = adapter.primaryRow()) {
                    is AppAdapter.AppRow -> launchApp(row.entry, binding.searchInput)
                    is AppAdapter.CommandRow -> runCommand(row.command)
                    is AppAdapter.WebRow -> webSearch(row.query)
                    null -> {}
                }
                true
            } else {
                false
            }
        }
        binding.assistantBtn.setOnClickListener { launchAssistant() }
        binding.allAppsBtn.setOnClickListener { if (allAppsOpen) resetToHome() else openAllApps() }
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
        if (query.isEmpty() && !allAppsOpen) {
            binding.results.visibility = View.GONE
            binding.suggestionsBlock.visibility = View.VISIBLE
            startHints()
            return
        }
        val list = if (query.isEmpty()) {
            repo.visibleApps(prefs.hiddenApps).reversed() // reverseLayout: A at the bottom
        } else {
            repo.search(query, prefs.hiddenApps)
        }
        val commands = CommandProcessor.parse(query, commandCatalog, R.drawable.ic_calc, R.drawable.ic_link)
        adapter.submit(list, commands, webSearchQuery = query)
        binding.results.visibility = View.VISIBLE
        binding.suggestionsBlock.visibility = View.INVISIBLE
        binding.results.scrollToPosition(0)
        stopHints()
        binding.hintLeft.visibility = View.GONE
        binding.hintRight.visibility = View.GONE
    }

    private fun openAllApps() {
        allAppsOpen = true
        onQueryChanged(binding.searchInput.text.toString())
        focusSearch(show = true)
    }

    private fun resetToHome() {
        allAppsOpen = false
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

    private fun launchAssistant() {
        haptic(binding.assistantBtn)
        val candidates = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent("android.intent.action.ASSIST"),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
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

    /** Quick-action palette shown by the `>` prefix in the command bar. */
    private val commandCatalog: List<CommandProcessor.QuickAction> by lazy {
        listOf(
            CommandProcessor.QuickAction("lock", getString(R.string.cmd_lock), R.drawable.ic_lock, "lock secure"),
            CommandProcessor.QuickAction("quick_settings", getString(R.string.cmd_quick_settings), R.drawable.ic_tiles, "toggles panel"),
            CommandProcessor.QuickAction("notifications", getString(R.string.cmd_notifications), R.drawable.ic_bell, "shade alerts"),
            CommandProcessor.QuickAction("flashlight", getString(R.string.cmd_flashlight), R.drawable.ic_flashlight, "torch light"),
            CommandProcessor.QuickAction("wifi", getString(R.string.cmd_wifi), R.drawable.ic_wifi, "network internet"),
            CommandProcessor.QuickAction("bluetooth", getString(R.string.cmd_bluetooth), R.drawable.ic_bluetooth, "bt"),
            CommandProcessor.QuickAction("battery", getString(R.string.cmd_battery), R.drawable.ic_battery, "power"),
            CommandProcessor.QuickAction("assistant", getString(R.string.cmd_assistant), R.drawable.ic_assistant, "gemini voice"),
            CommandProcessor.QuickAction("all_apps", getString(R.string.cmd_all_apps), R.drawable.ic_apps, "drawer list"),
            CommandProcessor.QuickAction("settings", getString(R.string.cmd_settings), R.drawable.ic_settings, "system android"),
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
        }
    }

    private fun runQuickAction(id: String) {
        when (id) {
            "lock" -> lockScreen()
            "quick_settings" -> if (!LockService.openQuickSettings()) LockService.openNotificationShade(this)
            "notifications" -> LockService.openNotificationShade(this)
            "flashlight" -> toggleFlashlight()
            "wifi" -> openSettingsAction(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)
            "battery" -> openSettingsAction(Intent.ACTION_POWER_USAGE_SUMMARY)
            "assistant" -> launchAssistant()
            "all_apps" -> openAllApps()
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
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // no browser
        }
    }

    private fun webSearch(query: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_WEB_SEARCH)
                    .putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                // no browser: nothing sensible to do
            }
        }
    }

    /** Tapping the clock opens the configured app (system clock by default). */
    private fun launchClockTarget() {
        haptic(binding.clock)
        val target = prefs.clockTapTarget
        if (target.isNotEmpty()) {
            repo.apps.firstOrNull { it.componentKey == target }?.let {
                launchApp(it, binding.clock)
                return
            }
        }
        try {
            startActivity(
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // No clock app resolvable: nothing sensible to do.
        }
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
    }

    private fun stopHints() = hintHandler.removeCallbacks(hintRunnable)

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
        if (!prefs.showStatusLine) {
            binding.statusLine.visibility = View.GONE
            unregisterStatusReceiver()
            return
        }
        binding.statusLine.setTextColor(
            ColorUtils.setAlphaComponent(accentColor(), 0xC0)
        )
        refreshStatusLine()
        if (statusReceiver == null) {
            statusReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: Intent?) = refreshStatusLine()
            }
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(statusReceiver, filter)
        }
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
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL

        PredictionEngine.launchesToday(this) { launches ->
            val text = StatusLine.build(
                percent, charging, currentNet(), nextAlarmText(), launches
            )
            binding.statusLine.text = text
            binding.statusLine.visibility = View.VISIBLE
        }
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
        binding.assistantBtn.setColorFilter(ColorUtils.setAlphaComponent(accent, 0xCC))
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
