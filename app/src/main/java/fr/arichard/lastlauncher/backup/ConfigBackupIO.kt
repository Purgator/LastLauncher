package fr.arichard.lastlauncher.backup

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import fr.arichard.lastlauncher.BuildConfig
import fr.arichard.lastlauncher.calendar.CalendarFeed
import fr.arichard.lastlauncher.settings.Prefs
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Android/file/JSON side of [ConfigBackup]: builds the export file (shared
 * via FileProvider, same pattern as the prediction engine's brain export) and
 * reads an imported one back. Kept separate from [ConfigBackup] so that pure
 * logic stays plain-JVM unit-testable without touching `Looper`/`Context`.
 */
object ConfigBackupIO {

    private const val TAG = "ConfigBackupIO"
    const val FILE_NAME = "lastlauncher-config.json"

    private val executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "config-backup") } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Builds the export file (cache dir, shared via FileProvider like the brain
     *  export) and hands it back on the main thread; null on failure. */
    fun export(context: Context, callback: (File?) -> Unit) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        CalendarFeed.calendars(appContext) { calendars ->
            val excludedIds = prefs.agendaExcludedCalendars
            val excludedNames = calendars.filter { it.id.toString() in excludedIds }.map { it.name }
            executor.execute {
                val file = try {
                    val payload = ConfigBackup.buildPayload(
                        prefs.rawAll(), excludedNames, BuildConfig.VERSION_NAME
                    )
                    val dir = File(appContext.cacheDir, "export").apply { mkdirs() }
                    File(dir, FILE_NAME).apply { writeText(toJson(payload).toString(2)) }
                } catch (e: Exception) {
                    Log.w(TAG, "Config export failed", e)
                    null
                }
                main.post { callback(file) }
            }
        }
    }

    sealed class ImportOutcome {
        data class Success(val schema: Int, val result: ConfigBackup.ImportResult) : ImportOutcome()
        data object Failed : ImportOutcome()
    }

    /** Reads [uri] (from the system document/share picker), applies every
     *  recognized preference, and remaps calendar exclusions by name against
     *  this device's calendars — all on a background thread, callback on main. */
    fun import(context: Context, uri: Uri, callback: (ImportOutcome) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val parsed = try {
                val text = appContext.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                }
                if (text == null) null else fromJson(JSONObject(text))
            } catch (e: Exception) {
                Log.w(TAG, "Config import failed", e)
                null
            }
            if (parsed == null) {
                main.post { callback(ImportOutcome.Failed) }
                return@execute
            }
            CalendarFeed.calendars(appContext) { calendars ->
                val nameToId = calendars.associate { it.name to it.id.toString() }
                val result = ConfigBackup.resolveImport(parsed.prefs, parsed.excludedCalendarNames, nameToId)
                val prefs = Prefs(appContext)
                prefs.rawRestore(result.applied)
                prefs.agendaExcludedCalendars = result.matchedCalendarIds
                main.post { callback(ImportOutcome.Success(parsed.schema, result)) }
            }
        }
    }

    // ------------------------------------------------------------------ JSON I/O

    private fun toJson(payload: ConfigBackup.ExportPayload): JSONObject = JSONObject().apply {
        put("schema", payload.schema)
        put("app_version", payload.appVersion)
        put("exported_at", payload.exportedAt)
        put("prefs", JSONObject().apply {
            for ((key, value) in payload.prefs) {
                put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
            }
        })
        put("excluded_calendar_names", JSONArray(payload.excludedCalendarNames))
    }

    private fun fromJson(json: JSONObject): ConfigBackup.ExportPayload {
        val prefsJson = json.optJSONObject("prefs") ?: JSONObject()
        val prefs = HashMap<String, Any?>()
        for (key in prefsJson.keys()) {
            val value = prefsJson.get(key)
            prefs[key] = if (value is JSONArray) {
                (0 until value.length()).map { value.getString(it) }
            } else {
                value
            }
        }
        val namesJson = json.optJSONArray("excluded_calendar_names")
        val names = namesJson?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList()
        return ConfigBackup.ExportPayload(
            json.optInt("schema", 1),
            json.optString("app_version", ""),
            json.optLong("exported_at", 0L),
            prefs,
            names,
        )
    }
}
