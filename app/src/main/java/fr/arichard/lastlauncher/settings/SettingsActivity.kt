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

            findPreference<Preference>("drawer_apps")?.setOnPreferenceClickListener {
                showDrawerAppsDialog()
                true
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

        override fun onDestroy() {
            executor.shutdown()
            super.onDestroy()
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
            pref.summary = when {
                spec.action == GestureAction.OPEN_APP -> {
                    val app = repo.apps.firstOrNull { it.componentKey == spec.appKey }
                    if (app != null) getString(R.string.gesture_open_app_named, app.label)
                    else getString(GestureAction.NONE.labelRes)
                }
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
                val action = actions[which]
                if (action == GestureAction.OPEN_APP) {
                    pickGestureApp(pref, key)
                } else {
                    saveGesture(key, GestureBinding(action))
                    updateGestureSummary(pref, key)
                }
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
            pref.summary = repo.apps.firstOrNull { it.componentKey == target }?.label
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

        private fun showDrawerAppsDialog() {
            val context = requireContext()
            val repo = (context.applicationContext as LauncherApp).repo
            val prefs = Prefs(context)
            val apps = repo.apps
            if (apps.isEmpty()) return
            val current = prefs.drawerApps
            val items = apps.map { AppPickerDialog.Item(it.label, repo.icon(it)) }
            val checked = BooleanArray(apps.size) { apps[it].componentKey in current }
            AppPickerDialog.multiChoice(
                context, getString(R.string.pref_drawer_apps), items, checked
            ) {
                prefs.drawerApps = apps.indices.filter { checked[it] }.map { apps[it].componentKey }
            }
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
