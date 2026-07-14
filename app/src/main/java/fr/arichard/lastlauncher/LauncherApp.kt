package fr.arichard.lastlauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import fr.arichard.lastlauncher.apps.AppRepository
import fr.arichard.lastlauncher.predict.ContextSignals

class LauncherApp : Application() {

    lateinit var repo: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = AppRepository(this)
        repo.load()
        ContextSignals.register(this)
        registerPackageChanges()
    }

    private fun registerPackageChanges() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // A genuinely new install (not an update) enters the spotlight.
                if (intent.action == Intent.ACTION_PACKAGE_ADDED &&
                    !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                ) {
                    intent.data?.schemeSpecificPart?.let {
                        fr.arichard.lastlauncher.settings.Prefs(context).addNewApp(it)
                    }
                }
                repo.load()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(receiver, filter)
    }
}
