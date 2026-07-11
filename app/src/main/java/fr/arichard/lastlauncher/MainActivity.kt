package fr.arichard.lastlauncher

import android.Manifest
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import fr.arichard.lastlauncher.databinding.ActivityMainBinding
import fr.arichard.lastlauncher.lock.LockService
import fr.arichard.lastlauncher.predict.PredictionEngine
import fr.arichard.lastlauncher.settings.Prefs
import fr.arichard.lastlauncher.settings.SettingsActivity
import fr.arichard.lastlauncher.ui.AppAdapter
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
        refreshSuggestions()
        maybeRequestBluetoothPermission()
        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already home: return to the clean state.
        resetToHome()
    }

    @Deprecated("Deprecated in Java")
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

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (prefs.doubleTapLock) lockScreen()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                haptic(binding.root)
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float,
            ): Boolean {
                if (e1 == null || abs(vy) < 800 || abs(vy) < abs(vx)) return false
                if (vy < 0) {
                    openAllApps()
                } else if (prefs.swipeDownNotifications) {
                    LockService.openNotificationShade(this@MainActivity)
                }
                return true
            }
        })
        binding.root.isClickable = true
        binding.root.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            detector.onTouchEvent(event)
            true
        }
    }

    private fun setupSearch() {
        adapter = AppAdapter(
            repo,
            onClick = { entry, view -> launchApp(entry, view) },
            onLongClick = { entry, view -> showAppMenu(entry, view) },
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
                adapter.firstApp()?.let { launchApp(it, binding.searchInput) }
                    ?: adapter.currentWebQuery()?.let { webSearch(it) }
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
            return
        }
        val list = if (query.isEmpty()) {
            repo.visibleApps(prefs.hiddenApps).reversed() // reverseLayout: A at the bottom
        } else {
            repo.search(query, prefs.hiddenApps)
        }
        adapter.submit(list, webSearchQuery = query)
        binding.results.visibility = View.VISIBLE
        binding.suggestionsBlock.visibility = View.INVISIBLE
        binding.results.scrollToPosition(0)
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
            suggestions = listOf(null, null, null)
            binding.suggestMain.visibility = View.INVISIBLE
            binding.suggestLeft.visibility = View.INVISIBLE
            binding.suggestRight.visibility = View.INVISIBLE
            return
        }
        PredictionEngine.computeSuggestions(this, 3) { ranked, scores ->
            repo.usageBoost = scores
            val entries = ranked.map { repo.byPackage(it) }
            suggestions = entries
            bindSuggestion(
                entries.getOrNull(0), binding.suggestMain,
                binding.suggestMainIcon, binding.suggestMainLabel
            )
            bindSuggestion(
                entries.getOrNull(1), binding.suggestLeft,
                binding.suggestLeftIcon, binding.suggestLeftLabel
            )
            bindSuggestion(
                entries.getOrNull(2), binding.suggestRight,
                binding.suggestRightIcon, binding.suggestRightLabel
            )
            if (prefs.animations) animateSuggestionsIn()
        }
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
