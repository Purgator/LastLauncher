package fr.arichard.lastlauncher.calendar

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure agenda logic: turns raw calendar event instances into the ordered row list
 * the home-screen stream renders (day separators, next-event marker, countdown).
 * No Android types beyond java.util, so all of it is unit-tested.
 */
object Agenda {

    /** One event instance pulled from the calendar provider. */
    data class EventInstance(
        val eventId: Long,
        val begin: Long,
        val end: Long,
        val title: String,
        val location: String,
        val allDay: Boolean,
        val calendarId: Long,
    )

    enum class DayKind { TOMORROW, LATER }

    sealed class Row {
        /** Separator before the first event of a non-today day. */
        data class DayHeader(val kind: DayKind, val dayStart: Long) : Row()

        /**
         * An event line. [next] marks the single highlighted upcoming event;
         * [ongoing] means it has started but not ended.
         */
        data class Event(
            val event: EventInstance, val next: Boolean, val ongoing: Boolean,
        ) : Row()
    }

    /**
     * Builds the stream: ended events dropped, remaining sorted (all-day first
     * within each day), day separators inserted, the first timed event that hasn't
     * ended flagged as [Row.Event.next] (falling back to the first row at all).
     */
    fun rows(
        events: List<EventInstance>,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Row> {
        val live = events
            .map { if (it.allDay) it.copy(begin = allDayToLocal(it.begin, zone)) else it }
            .filter { eventEnd(it, zone) > now }
            .sortedWith(compareBy({ dayStart(it.begin, zone) }, { !it.allDay }, { it.begin }))
        if (live.isEmpty()) return emptyList()

        val nextEvent = live.firstOrNull { !it.allDay } ?: live.first()
        val todayStart = dayStart(now, zone)
        val tomorrowStart = plusDays(todayStart, 1, zone)
        val result = ArrayList<Row>(live.size + 4)
        var lastDay = todayStart
        for (event in live) {
            val day = dayStart(event.begin, zone)
            if (day != lastDay) {
                val kind = if (day == tomorrowStart) DayKind.TOMORROW else DayKind.LATER
                result.add(Row.DayHeader(kind, day))
                lastDay = day
            }
            result.add(
                Row.Event(
                    event,
                    next = event === nextEvent,
                    ongoing = !event.allDay && event.begin <= now,
                )
            )
        }
        return result
    }

    /** Minutes from [now] until [begin], rounded up; never negative. */
    fun minutesUntil(begin: Long, now: Long): Int =
        (((begin - now).coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS).toInt()

    /**
     * The provider stores all-day instances as UTC midnights; re-anchor to the
     * same date's local midnight so day grouping and ordering are correct.
     */
    fun allDayToLocal(utcMidnight: Long, zone: TimeZone): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = utcMidnight
        val local = Calendar.getInstance(zone)
        local.clear()
        local.set(
            utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH)
        )
        return local.timeInMillis
    }

    /** An all-day event "ends" at its local day's end; timed events at their end. */
    private fun eventEnd(event: EventInstance, zone: TimeZone): Long =
        if (event.allDay) plusDays(dayStart(event.begin, zone), 1, zone) else event.end

    /** Local midnight of the day containing [time]. */
    private fun dayStart(time: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = time
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Calendar-correct day stepping (DST days are not 24 h in Europe/Paris). */
    private fun plusDays(dayStart: Long, days: Int, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = dayStart
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }

    private const val MINUTE_MS = 60_000L
}
