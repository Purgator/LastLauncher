package fr.arichard.lastlauncher.settings

import android.app.role.RoleManager
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
import fr.arichard.lastlauncher.gesture.GestureAction
import fr.arichard.lastlauncher.gesture.GestureBinding
import fr.arichard.lastlauncher.lock.LockService
import fr.arichard.lastlauncher.notify.NotifListener
import fr.arichard.lastlauncher.predict.PredictionEngine
import fr.arichard.lastlauncher.ui.AppPickerDialog
import fr.arichard.lastlauncher.update.UpdateManager
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val executor = Executors.newSingleThreadExecutor()

        private val GESTURE_KEYS = listOf(
            Prefs.KEY_GESTURE_LR_1, Prefs.KEY_GESTURE_LR_2,
            Prefs.KEY_GESTURE_RL_1, Prefs.KEY_GESTURE_RL_2,
        )

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME

            findPreference<Preference>("default_launcher")?.setOnPreferenceClickListener {
                requestDefaultLauncher()
                true
            }

            findPreference<Preference>("reset_learning")?.setOnPreferenceClickListener {
                confirmResetLearning()
                true
            }

            findPreference<Preference>("hidden_apps")?.setOnPreferenceClickListener {
                showHiddenAppsDialog()
                true
            }

            findPreference<Preference>("clock_tap")?.let { pref ->
                updateClockTapSummary(pref)
                pref.setOnPreferenceClickListener {
                    showClockTapDialog(pref)
                    true
                }
            }

            findPreference<Preference>("favorites")?.setOnPreferenceClickListener {
                showFavoritesDialog()
                true
            }

            findPreference<Preference>("drawers")?.setOnPreferenceClickListener {
                showDrawersDialog()
                true
            }

            findPreference<Preference>("notif_access")?.let { pref ->
                updateNotifAccessSummary(pref)
                pref.setOnPreferenceClickListener {
                    NotifListener.requestAccess(requireContext())
                    true
                }
            }

            findPreference<Preference>("check_now")?.setOnPreferenceClickListener { pref ->
                checkForUpdatesNow(pref)
                true
            }

            for (key in GESTURE_KEYS) {
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
                    if (newValue == true && !LockService.isRunning) {
                        promptEnableLockService()
                    }
                    true
                }
        }

        override fun onResume() {
            super.onResume()
            // Access is granted on another screen; refresh the summary on return.
            findPreference<Preference>("notif_access")?.let { updateNotifAccessSummary(it) }
        }

        override fun onDestroy() {
            executor.shutdown()
            super.onDestroy()
        }

        private fun updateNotifAccessSummary(pref: Preference) {
            pref.summary = if (NotifListener.hasAccess(requireContext())) {
                getString(R.string.pref_notif_access_on)
            } else {
                getString(R.string.pref_notif_access_summary)
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

        private fun confirmResetLearning() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_reset_learning)
                .setMessage(R.string.reset_learning_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    PredictionEngine.clearHistory(requireContext()) {
                        context?.let {
                            Toast.makeText(it, R.string.reset_learning_done, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun showHiddenAppsDialog() {
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val prefs = Prefs(context)
            val apps = repo.apps
            if (apps.isEmpty()) return
            val hidden = prefs.hiddenApps
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val checked = BooleanArray(apps.size) { apps[it].packageName in hidden }
            AppPickerDialog.multiChoice(
                context, getString(R.string.pref_hidden_apps), items, checked
            ) {
                prefs.hiddenApps = apps.indices
                    .filter { checked[it] }
                    .map { apps[it].packageName }
                    .toSet()
            }
        }

        private fun updateGestureSummary(pref: Preference, key: String) {
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val spec = GestureBinding.decode(Prefs(context).gestureBinding(key))
            pref.summary = when (spec.action) {
                GestureAction.OPEN_APP -> {
                    val app = spec.appKey?.let { repo.byComponentKey(it) }
                    if (app != null) getString(R.string.gesture_open_app_named, app.label)
                    else getString(GestureAction.NONE.labelRes)
                }
                GestureAction.APP_DRAWER ->
                    getString(R.string.drawer_action_named, Prefs(context).drawer(spec.drawerIndex).name)
                else -> getString(spec.action.labelRes)
            }
        }

        private fun showGestureDialog(pref: Preference, key: String) {
            val context = requireContext()
            val actions = GestureAction.entries
            val current = GestureBinding.decode(Prefs(context).gestureBinding(key)).action
            val items = actions.map {
                AppPickerDialog.Item(getString(it.labelRes), null)
            }
            val selected = actions.indexOf(current).coerceAtLeast(0)
            AppPickerDialog.singleChoice(
                context, pref.title ?: "", items, selected
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

        /** Bind an edge swipe to a specific drawer (or the only one, without asking). */
        private fun pickGestureDrawer(pref: Preference, key: String) {
            val context = requireContext()
            val drawers = Prefs(context).drawers()
            if (drawers.size == 1) {
                saveGesture(key, GestureBinding(GestureAction.APP_DRAWER, "0"))
                updateGestureSummary(pref, key)
                return
            }
            val items = drawers.map { AppPickerDialog.Item(it.name, null) }
            AppPickerDialog.singleChoice(
                context, getString(R.string.drawer_pick_for_gesture), items, -1
            ) { which ->
                saveGesture(key, GestureBinding(GestureAction.APP_DRAWER, drawers[which].index.toString()))
                updateGestureSummary(pref, key)
            }
        }

        private fun pickGestureApp(pref: Preference, key: String) {
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val apps = repo.apps
            if (apps.isEmpty()) return
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            AppPickerDialog.singleChoice(
                context, getString(R.string.gesture_open_app), items, -1
            ) { which ->
                saveGesture(key, GestureBinding(GestureAction.OPEN_APP, apps[which].componentKey))
                updateGestureSummary(pref, key)
            }
        }

        private fun saveGesture(key: String, binding: GestureBinding) {
            preferenceManager.sharedPreferences?.edit()
                ?.putString(key, binding.encode())?.apply()
        }

        private fun updateClockTapSummary(pref: Preference) {
            val context = requireContext()
            val target = Prefs(context).clockTapTarget
            val repo = (context.applicationContext as LauncherApp).repo
            pref.summary = repo.byComponentKey(target)?.label
                ?: getString(R.string.clock_tap_default)
        }

        private fun showClockTapDialog(pref: Preference) {
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val prefs = Prefs(context)
            val apps = repo.apps
            val clockIcon = androidx.core.content.ContextCompat.getDrawable(
                context, R.drawable.ic_clock
            )
            val items = listOf(AppPickerDialog.Item(getString(R.string.clock_tap_default), clockIcon)) +
                apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val current = prefs.clockTapTarget
            val checkedIndex = apps.indexOfFirst { it.componentKey == current } + 1
            AppPickerDialog.singleChoice(
                context, getString(R.string.pref_clock_tap), items, checkedIndex
            ) { which ->
                val value = if (which == 0) "" else apps[which - 1].componentKey
                preferenceManager.sharedPreferences?.edit()
                    ?.putString(Prefs.KEY_CLOCK_TAP, value)?.apply()
                updateClockTapSummary(pref)
            }
        }

        private fun showFavoritesDialog() {
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val prefs = Prefs(context)
            val apps = repo.apps
            if (apps.isEmpty()) return
            val favorites = prefs.favorites
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val checked = BooleanArray(apps.size) { apps[it].packageName in favorites }
            AppPickerDialog.multiChoice(
                context, getString(R.string.pref_favorites), items, checked
            ) {
                prefs.favorites = apps.indices.filter { checked[it] }.map { apps[it].packageName }
                prefs.onboardingDone = true
            }
        }

        /** Lists the configured drawers; pick one to edit, or add a new one. */
        private fun showDrawersDialog() {
            val context = requireContext()
            val prefs = Prefs(context)
            val drawers = prefs.drawers()
            val labels = drawers.map {
                AppPickerDialog.Item(it.name, null)
            } + AppPickerDialog.Item("＋ " + getString(R.string.drawer_add), null)
            AppPickerDialog.singleChoice(
                context, getString(R.string.pref_drawers), labels, -1
            ) { which ->
                if (which < drawers.size) editDrawer(drawers[which].index)
                else addDrawerFlow()
            }
        }

        private fun addDrawerFlow() {
            val context = requireContext()
            promptText(getString(R.string.drawer_add_title), "") { name ->
                val index = Prefs(context).addDrawer(name)
                if (index >= 0) editDrawer(index)
                else Toast.makeText(context, R.string.pref_drawers, Toast.LENGTH_SHORT).show()
            }
        }

        /** Edit one drawer: choose its apps, plus rename / delete actions. */
        private fun editDrawer(index: Int) {
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val prefs = Prefs(context)
            val apps = repo.apps
            if (apps.isEmpty()) return
            val drawer = prefs.drawer(index)
            val actions = mutableListOf(
                AppPickerDialog.Item(getString(R.string.drawer_edit_apps), null),
                AppPickerDialog.Item(getString(R.string.drawer_rename), null),
            )
            if (prefs.drawers().size > 1) {
                actions.add(AppPickerDialog.Item(getString(R.string.drawer_delete), null))
            }
            AppPickerDialog.singleChoice(context, drawer.name, actions, -1) { which ->
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
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val prefs = Prefs(context)
            val apps = repo.apps
            val drawer = prefs.drawer(index)
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val checked = BooleanArray(apps.size) { apps[it].componentKey in drawer.apps }
            AppPickerDialog.multiChoice(context, drawer.name, items, checked) {
                prefs.saveDrawer(
                    index, drawer.name,
                    apps.indices.filter { checked[it] }.map { apps[it].componentKey }
                )
            }
        }

        /** Small single-field text prompt for drawer names. */
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
}
