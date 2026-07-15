package fr.arichard.lastlauncher.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.arichard.lastlauncher.BuildConfig
import fr.arichard.lastlauncher.LauncherApp
import fr.arichard.lastlauncher.R
import fr.arichard.lastlauncher.apps.AppRepository
import fr.arichard.lastlauncher.calendar.CalendarFeed
import fr.arichard.lastlauncher.gesture.GestureAction
import fr.arichard.lastlauncher.gesture.GestureBinding
import fr.arichard.lastlauncher.lock.LockService
import fr.arichard.lastlauncher.notify.NotifListener
import fr.arichard.lastlauncher.predict.PredictionEngine
import fr.arichard.lastlauncher.ui.AppPickerDialog
import fr.arichard.lastlauncher.update.UpdateManager
import fr.arichard.lastlauncher.weather.WeatherProvider
import java.util.concurrent.Executors

/**
 * Settings, organised as a root list of domains with one subscreen each. Long-pressing
 * a home-screen element deep-links straight into its domain via [open].
 */
class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val fragment = fragmentForScreen(intent.getStringExtra(EXTRA_SCREEN))
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat, pref: Preference,
    ): Boolean {
        // Map keys ("screen_general" → GeneralFragment) to direct constructors instead
        // of reflectively instantiating pref.fragment: R8 strips classes that are only
        // referenced by name strings in XML, which crashed release builds here.
        if (pref.fragment == null) return false
        val fragment = fragmentForScreen(pref.key.removePrefix("screen_"))
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    private fun fragmentForScreen(screen: String?): PreferenceFragmentCompat = when (screen) {
        SCREEN_GENERAL -> GeneralFragment()
        SCREEN_CLOCK_WEATHER -> ClockWeatherFragment()
        SCREEN_SUGGESTIONS -> SuggestionsFragment()
        SCREEN_AGENDA -> AgendaFragment()
        SCREEN_GESTURES -> GesturesFragment()
        SCREEN_APPEARANCE -> AppearanceFragment()
        SCREEN_APPS -> AppsFragment()
        SCREEN_NOTIFICATIONS -> NotificationsFragment()
        SCREEN_UPDATES -> UpdatesFragment()
        else -> RootFragment()
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"
        const val SCREEN_GENERAL = "general"
        const val SCREEN_CLOCK_WEATHER = "clock_weather"
        const val SCREEN_SUGGESTIONS = "suggestions"
        const val SCREEN_AGENDA = "agenda"
        const val SCREEN_GESTURES = "gestures"
        const val SCREEN_APPEARANCE = "appearance"
        const val SCREEN_APPS = "apps"
        const val SCREEN_NOTIFICATIONS = "notifications"
        const val SCREEN_UPDATES = "updates"

        /** Opens the settings, optionally straight onto one domain's subscreen. */
        fun open(context: Context, screen: String? = null) {
            context.startActivity(
                Intent(context, SettingsActivity::class.java).apply {
                    screen?.let { putExtra(EXTRA_SCREEN, it) }
                }
            )
        }
    }

    /** Shared plumbing for every settings screen. */
    abstract class BaseFragment : PreferenceFragmentCompat() {

        protected val repo: AppRepository
            get() = (requireContext().applicationContext as LauncherApp).repo

        protected val prefs: Prefs get() = Prefs(requireContext())

        protected fun putString(key: String, value: String) {
            preferenceManager.sharedPreferences?.edit()?.putString(key, value)?.apply()
        }

        /** Multi-choice app picker bound to a package-name set. */
        protected fun pickAppsByPackage(
            title: CharSequence, current: Collection<String>, onSave: (List<String>) -> Unit,
        ) {
            val apps = repo.apps
            if (apps.isEmpty()) return
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val checked = BooleanArray(apps.size) { apps[it].packageName in current }
            AppPickerDialog.multiChoice(requireContext(), title, items, checked) {
                onSave(apps.indices.filter { checked[it] }.map { apps[it].packageName })
            }
        }

        protected fun icon(res: Int) =
            androidx.core.content.ContextCompat.getDrawable(requireContext(), res)

        /** Single-choice picker of an app component with a "default" first row. */
        protected fun pickComponentWithDefault(
            title: CharSequence, defaultLabel: String, currentKey: String,
            onSave: (String) -> Unit,
        ) {
            val apps = repo.apps
            val items = listOf(AppPickerDialog.Item(defaultLabel, null)) +
                apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val checkedIndex = apps.indexOfFirst { it.componentKey == currentKey } + 1
            AppPickerDialog.singleChoice(requireContext(), title, items, checkedIndex) { which ->
                onSave(if (which == 0) "" else apps[which - 1].componentKey)
            }
        }
    }

    class RootFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("insights")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), InsightsActivity::class.java))
                true
            }
        }
    }

    class GeneralFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_general, rootKey)
            findPreference<Preference>("default_launcher")?.setOnPreferenceClickListener {
                requestDefaultLauncher()
                true
            }
        }

        private fun requestDefaultLauncher() {
            val context = requireContext()
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val rm = context.getSystemService(RoleManager::class.java)
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) &&
                        !rm.isRoleHeld(RoleManager.ROLE_HOME)
                    ) {
                        startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                        return
                    }
                }
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    class ClockWeatherFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_clock_weather, rootKey)

            findPreference<Preference>("clock_tap")?.let { pref ->
                updateClockTapSummary(pref)
                pref.setOnPreferenceClickListener {
                    pickComponentWithDefault(
                        pref.title ?: "", getString(R.string.clock_tap_default),
                        prefs.clockTapTarget,
                    ) { value ->
                        putString(Prefs.KEY_CLOCK_TAP, value)
                        updateClockTapSummary(pref)
                    }
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("weather_enabled")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true &&
                        !WeatherProvider.hasLocationPermission(requireContext())
                    ) {
                        requestPermissions(
                            arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 42
                        )
                        Toast.makeText(
                            requireContext(), R.string.weather_needs_location, Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }

            findPreference<Preference>("weather_tap")?.let { pref ->
                updateWeatherTapSummary(pref)
                pref.setOnPreferenceClickListener {
                    pickComponentWithDefault(
                        pref.title ?: "", getString(R.string.weather_tap_default),
                        prefs.weatherTapTarget,
                    ) { value ->
                        putString(Prefs.KEY_WEATHER_TAP, value)
                        updateWeatherTapSummary(pref)
                    }
                    true
                }
            }
        }

        private fun updateClockTapSummary(pref: Preference) {
            pref.summary = repo.byComponentKey(prefs.clockTapTarget)?.label
                ?: getString(R.string.clock_tap_default)
        }

        private fun updateWeatherTapSummary(pref: Preference) {
            pref.summary = repo.byComponentKey(prefs.weatherTapTarget)?.label
                ?: getString(R.string.weather_tap_default)
        }
    }

    class SuggestionsFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_suggestions, rootKey)

            findPreference<Preference>("favorites")?.setOnPreferenceClickListener {
                pickAppsByPackage(getString(R.string.pref_favorites), prefs.favorites) {
                    prefs.favorites = it
                    prefs.onboardingDone = true
                }
                true
            }

            findPreference<Preference>("boosted_apps")?.setOnPreferenceClickListener {
                pickAppsByPackage(getString(R.string.pref_boosted), prefs.boostedApps) {
                    prefs.boostedApps = it.toSet()
                }
                true
            }

            findPreference<Preference>("insights")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), InsightsActivity::class.java))
                true
            }

            findPreference<Preference>("reset_learning")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_reset_learning)
                    .setMessage(R.string.reset_learning_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        PredictionEngine.clearHistory(requireContext()) {
                            context?.let {
                                Toast.makeText(
                                    it, R.string.reset_learning_done, Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
        }
    }

    class AgendaFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_agenda, rootKey)

            findPreference<SwitchPreferenceCompat>("agenda_enabled")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true &&
                        !CalendarFeed.hasPermission(requireContext())
                    ) {
                        requestPermissions(
                            arrayOf(android.Manifest.permission.READ_CALENDAR), 43
                        )
                        Toast.makeText(
                            requireContext(), R.string.agenda_needs_permission, Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }

            findPreference<Preference>("agenda_calendars")?.setOnPreferenceClickListener {
                pickCalendars()
                true
            }
        }

        /** Multi-choice over the device's calendars; unchecked ones are excluded. */
        private fun pickCalendars() {
            val context = requireContext()
            if (!CalendarFeed.hasPermission(context)) {
                Toast.makeText(context, R.string.agenda_needs_permission, Toast.LENGTH_SHORT)
                    .show()
                return
            }
            CalendarFeed.calendars(context) { calendars ->
                if (!isAdded || calendars.isEmpty()) return@calendars
                val excluded = prefs.agendaExcludedCalendars
                val items = calendars.map { AppPickerDialog.Item(it.name, null) }
                val checked = BooleanArray(calendars.size) {
                    calendars[it].id.toString() !in excluded
                }
                AppPickerDialog.multiChoice(
                    requireContext(), getString(R.string.pref_agenda_calendars), items, checked
                ) {
                    prefs.agendaExcludedCalendars = calendars.indices
                        .filter { !checked[it] }
                        .map { calendars[it].id.toString() }
                        .toSet()
                }
            }
        }
    }

    class GesturesFragment : BaseFragment() {

        private val gestureKeys = listOf(
            Prefs.KEY_GESTURE_LR_1, Prefs.KEY_GESTURE_LR_2,
            Prefs.KEY_GESTURE_RL_1, Prefs.KEY_GESTURE_RL_2,
        )

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_gestures, rootKey)

            for (key in gestureKeys) {
                findPreference<Preference>(key)?.let { pref ->
                    updateGestureSummary(pref, key)
                    pref.setOnPreferenceClickListener {
                        showGestureDialog(pref, key)
                        true
                    }
                }
            }

            findPreference<SwitchPreferenceCompat>("double_tap_lock")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true && !LockService.isRunning) promptEnableLockService()
                    true
                }
        }

        private fun updateGestureSummary(pref: Preference, key: String) {
            val spec = GestureBinding.decode(prefs.gestureBinding(key))
            pref.summary = when (spec.action) {
                GestureAction.OPEN_APP -> {
                    val app = spec.appKey?.let { repo.byComponentKey(it) }
                    if (app != null) getString(R.string.gesture_open_app_named, app.label)
                    else getString(GestureAction.NONE.labelRes)
                }
                GestureAction.APP_DRAWER ->
                    getString(R.string.drawer_action_named, prefs.drawer(spec.drawerIndex).name)
                else -> getString(spec.action.labelRes)
            }
        }

        private fun showGestureDialog(pref: Preference, key: String) {
            val actions = GestureAction.entries
            val current = GestureBinding.decode(prefs.gestureBinding(key)).action
            val items = actions.map {
                AppPickerDialog.Item(
                    getString(it.labelRes),
                    if (it.iconRes != 0) icon(it.iconRes) else null
                )
            }
            AppPickerDialog.singleChoice(
                requireContext(), pref.title ?: "", items,
                actions.indexOf(current).coerceAtLeast(0)
            ) { which ->
                when (val action = actions[which]) {
                    GestureAction.OPEN_APP -> pickGestureApp(pref, key)
                    GestureAction.APP_DRAWER -> pickGestureDrawer(pref, key)
                    else -> {
                        saveGesture(key, GestureBinding(action))
                        updateGestureSummary(pref, key)
                    }
                }
            }
        }

        private fun pickGestureApp(pref: Preference, key: String) {
            val apps = repo.apps
            if (apps.isEmpty()) return
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            AppPickerDialog.singleChoice(
                requireContext(), getString(R.string.gesture_open_app), items, -1
            ) { which ->
                saveGesture(key, GestureBinding(GestureAction.OPEN_APP, apps[which].componentKey))
                updateGestureSummary(pref, key)
            }
        }

        private fun pickGestureDrawer(pref: Preference, key: String) {
            val drawers = prefs.drawers()
            if (drawers.size == 1) {
                saveGesture(key, GestureBinding(GestureAction.APP_DRAWER, "0"))
                updateGestureSummary(pref, key)
                return
            }
            val items = drawers.map { AppPickerDialog.Item(it.name, null) }
            AppPickerDialog.singleChoice(
                requireContext(), getString(R.string.drawer_pick_for_gesture), items, -1
            ) { which ->
                saveGesture(
                    key, GestureBinding(GestureAction.APP_DRAWER, drawers[which].index.toString())
                )
                updateGestureSummary(pref, key)
            }
        }

        private fun saveGesture(key: String, binding: GestureBinding) {
            putString(key, binding.encode())
        }

        private fun promptEnableLockService() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lock_needs_service_title)
                .setMessage(R.string.lock_needs_service_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    LockService.openAccessibilitySettings(requireContext())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    class AppearanceFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_appearance, rootKey)
        }
    }

    class AppsFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_apps, rootKey)

            findPreference<Preference>("hidden_apps")?.setOnPreferenceClickListener {
                pickAppsByPackage(getString(R.string.pref_hidden_apps), prefs.hiddenApps) {
                    prefs.hiddenApps = it.toSet()
                }
                true
            }

            findPreference<Preference>("drawers")?.setOnPreferenceClickListener {
                showDrawersDialog()
                true
            }
        }

        /** Lists the configured drawers; pick one to edit, or add a new one. */
        private fun showDrawersDialog() {
            val drawers = prefs.drawers()
            val labels = drawers.map {
                AppPickerDialog.Item(it.name, icon(R.drawable.ic_apps))
            } + AppPickerDialog.Item("＋ " + getString(R.string.drawer_add), icon(R.drawable.ic_edit))
            AppPickerDialog.singleChoice(
                requireContext(), getString(R.string.pref_drawers), labels, -1
            ) { which ->
                if (which < drawers.size) editDrawer(drawers[which].index)
                else promptText(getString(R.string.drawer_add_title), "") { name ->
                    val index = prefs.addDrawer(name)
                    if (index >= 0) editDrawer(index)
                }
            }
        }

        private fun editDrawer(index: Int) {
            val drawer = prefs.drawer(index)
            val actions = mutableListOf(
                AppPickerDialog.Item(getString(R.string.drawer_edit_apps), icon(R.drawable.ic_apps)),
                AppPickerDialog.Item(getString(R.string.drawer_rename), icon(R.drawable.ic_edit)),
            )
            if (prefs.drawers().size > 1) {
                actions.add(
                    AppPickerDialog.Item(getString(R.string.drawer_delete), icon(R.drawable.ic_delete))
                )
            }
            AppPickerDialog.singleChoice(requireContext(), drawer.name, actions, -1) { which ->
                when (which) {
                    0 -> pickDrawerApps(index)
                    1 -> promptText(getString(R.string.drawer_rename), drawer.name) { name ->
                        prefs.saveDrawer(index, name, drawer.apps)
                    }
                    2 -> prefs.deleteDrawer(index)
                }
            }
        }

        private fun pickDrawerApps(index: Int) {
            val apps = repo.apps
            val drawer = prefs.drawer(index)
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val checked = BooleanArray(apps.size) { apps[it].componentKey in drawer.apps }
            AppPickerDialog.multiChoice(requireContext(), drawer.name, items, checked) {
                prefs.saveDrawer(
                    index, drawer.name,
                    apps.indices.filter { checked[it] }.map { apps[it].componentKey }
                )
            }
        }

        private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
            val context = requireContext()
            val input = android.widget.EditText(context).apply {
                setText(initial)
                setSingleLine()
                setHint(R.string.drawer_name_hint)
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val container = android.widget.FrameLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    onOk(input.text.toString().trim())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    class NotificationsFragment : BaseFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_notifications, rootKey)
            findPreference<Preference>("notif_access")?.setOnPreferenceClickListener {
                NotifListener.requestAccess(requireContext())
                true
            }
        }

        override fun onResume() {
            super.onResume()
            // Access is granted on another screen; refresh the summary on return.
            findPreference<Preference>("notif_access")?.let { pref ->
                pref.summary = if (NotifListener.hasAccess(requireContext())) {
                    getString(R.string.pref_notif_access_on)
                } else {
                    getString(R.string.pref_notif_access_summary)
                }
            }
        }
    }

    class UpdatesFragment : BaseFragment() {

        private val executor = Executors.newSingleThreadExecutor()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_updates, rootKey)
            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>("check_now")?.setOnPreferenceClickListener { pref ->
                checkForUpdatesNow(pref)
                true
            }
        }

        override fun onDestroy() {
            executor.shutdown()
            super.onDestroy()
        }

        private fun checkForUpdatesNow(pref: Preference) {
            val context = requireContext()
            pref.summary = getString(R.string.update_checking)
            pref.isEnabled = false
            executor.execute {
                val result = UpdateManager.check(context, allowDownload = true)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    pref.isEnabled = true
                    pref.summary = when (result.status) {
                        UpdateManager.Status.UP_TO_DATE ->
                            getString(R.string.update_up_to_date, result.version)
                        UpdateManager.Status.UPDATE_READY -> {
                            UpdateManager.install(context, result.version!!)
                            getString(R.string.update_found, result.version)
                        }
                        UpdateManager.Status.UPDATE_DEFERRED ->
                            getString(R.string.update_deferred, result.version)
                        UpdateManager.Status.ERROR ->
                            getString(R.string.update_error, result.detail ?: "?")
                    }
                }
            }
        }
    }
}
