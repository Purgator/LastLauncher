package fr.arichard.lastlauncher.command

import fr.arichard.lastlauncher.R

/**
 * What the command bar targets, cycled by the wheel on its left:
 *  - SMART: apps + settings + math + URLs, with long text routed to the assistant,
 *  - APPS: app search only,
 *  - SETTINGS: jump straight to a system setting or launcher quick action,
 *  - ASK: hand the query to the assistant.
 */
enum class SearchMode(val id: String, val iconRes: Int, val labelRes: Int) {
    SMART("smart", R.drawable.ic_mode_smart, R.string.mode_smart),
    APPS("apps", R.drawable.ic_apps, R.string.mode_apps),
    SETTINGS("settings", R.drawable.ic_settings, R.string.mode_settings),
    ASK("ask", R.drawable.ic_chat, R.string.mode_ask);

    fun next(): SearchMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byId(id: String?): SearchMode = entries.firstOrNull { it.id == id } ?: SMART
    }
}
