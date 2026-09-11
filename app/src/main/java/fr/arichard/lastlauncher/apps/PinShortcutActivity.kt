package fr.arichard.lastlauncher.apps

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import fr.arichard.lastlauncher.R

/**
 * Receives `ShortcutManager.requestPinShortcut()` confirmations from other apps.
 *
 * Its mere existence is the point: Android answers
 * `ShortcutManager.isRequestPinShortcutSupported()` by checking whether the default
 * launcher declares an activity for `CONFIRM_PIN_SHORTCUT`, and Chrome checks that
 * before offering "Install app" / "Add to Home screen" at all — without this,
 * installable PWAs simply cannot be installed while LastLauncher is the home app.
 * An installed PWA is a real package (WebAPK) and shows up like any other app.
 *
 * Plain home-screen shortcuts themselves (non-installable sites, contact
 * shortcuts…) have nowhere to live in a one-page launcher, so the request is
 * declined with an explanation rather than silently accepted into the void.
 */
class PinShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, R.string.pin_shortcut_unsupported, Toast.LENGTH_LONG).show()
        finish()
    }
}
