package fr.arichard.lastlauncher.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import java.util.concurrent.Executors

/**
 * Reads upcoming event instances from the system calendar provider. All queries run
 * on a dedicated executor and post results to the main thread; nothing here polls —
 * the host refreshes on resume and via a ContentObserver while in front.
 */
object CalendarFeed {

    data class CalendarInfo(val id: Long, val name: String)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar") }
    private val main = Handler(Looper.getMainLooper())

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Upcoming instances over the next [days], excluding [excludedCalendarIds]. */
    fun load(
        context: Context,
        days: Int,
        excludedCalendarIds: Set<String>,
        callback: (List<Agenda.EventInstance>) -> Unit,
    ) {
        if (!hasPermission(context)) {
            callback(emptyList())
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val events = try {
                query(appContext, days, excludedCalendarIds)
            } catch (e: Exception) {
                emptyList()
            }
            main.post { callback(events) }
        }
    }

    private fun query(
        context: Context, days: Int, excluded: Set<String>,
    ): List<Agenda.EventInstance> {
        val now = System.currentTimeMillis()
        // Window starts a day back so ongoing and today's all-day events (stored as
        // UTC midnights) are included; Agenda.rows() drops whatever already ended.
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - DAY_MS)
        ContentUris.appendId(builder, now + days * DAY_MS)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.VISIBLE,
        )
        val list = ArrayList<Agenda.EventInstance>()
        context.contentResolver.query(
            builder.build(), projection, null, null, CalendarContract.Instances.BEGIN
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(9) != 1) continue // hidden calendar
                if (cursor.getInt(7) == CalendarContract.Events.STATUS_CANCELED) continue
                if (cursor.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) {
                    continue
                }
                val calendarId = cursor.getLong(6)
                if (calendarId.toString() in excluded) continue
                list.add(
                    Agenda.EventInstance(
                        eventId = cursor.getLong(0),
                        begin = cursor.getLong(1),
                        end = cursor.getLong(2),
                        title = cursor.getString(3).orEmpty().ifBlank { "—" },
                        location = cursor.getString(4).orEmpty().trim(),
                        allDay = cursor.getInt(5) == 1,
                        calendarId = calendarId,
                    )
                )
            }
        }
        return list
    }

    /** The device's calendars, for the include/exclude picker in settings. */
    fun calendars(context: Context, callback: (List<CalendarInfo>) -> Unit) {
        if (!hasPermission(context)) {
            callback(emptyList())
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val list = ArrayList<CalendarInfo>()
            try {
                appContext.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    ),
                    null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        list.add(CalendarInfo(cursor.getLong(0), cursor.getString(1).orEmpty()))
                    }
                }
            } catch (e: Exception) {
                // fall through with what we have
            }
            main.post { callback(list) }
        }
    }

    private const val DAY_MS = 86_400_000L
}
