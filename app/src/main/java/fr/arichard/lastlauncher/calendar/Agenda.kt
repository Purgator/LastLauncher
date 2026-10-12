package fr.arichard.lastlauncher.calendar

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure agenda logic: turns raw calendar event instances into the ordered row list
 * the home-screen stream renders (day separators, next-event marker, countdown).
 * No Android types beyond java.util, so all of it is unit-tested.
 */
object Agenda {

    /**
     * One event instance pulled from the calendar provider. For all-day events
     * [begin] is re-anchored to local midnight for display; [providerBegin] keeps
     * the provider's raw UTC value, which calendar apps expect back in view
     * intents (a shifted value makes them fail to resolve the instance and fall
     * back to their slow main view).
     */
    data class EventInstance(
        val eventId: Long,
        val begin: Long,
        val end: Long,
        val title: String,
        val location: String,
        val allDay: Boolean,
        val calendarId: Long,
        val providerBegin: Long = begin,
    )

    enum class DayKind { TOMORROW, LATER }

    /**
     * How today's all-day events (birthdays, holidays…) get highlighted next to
     * the next timed event, so a same-day timed event never quietly outranks one:
     * [BOTH] flags both the same way (the default), [DISTINCT] gives all-day-today
     * its own marker so it never competes with the "next timed" one, and [SMART]
     * keeps a single highlight, letting all-day win it only while the next timed
     * event is still comfortably far off.
     */
    enum class AllDayHighlight { BOTH, DISTINCT, SMART }

    sealed class Row {
        /** Separator before the first event of a non-today day. */
        data class DayHeader(val kind: DayKind, val dayStart: Long) : Row()

        /**
         * An event line. [next] marks the highlighted upcoming event(s) — under
         * [AllDayHighlight.SMART] at most one row across the whole stream, under
         * [AllDayHighlight.BOTH] possibly two (the next timed event and today's
         * all-day event(s)). [allDayFeatured] is [AllDayHighlight.DISTINCT]'s own
         * marker for today's all-day events, independent of [next]. [ongoing]
         * means the event has started but not ended.
         *
         * An event spanning several days yields one row per day it covers:
         * [dayIndex] (0-based) of [dayCount]. [event] is the untouched instance
         * on every one of them — view intents need its real begin/end.
         */
        data class Event(
            val event: EventInstance,
            val next: Boolean,
            val ongoing: Boolean,
            val allDayFeatured: Boolean = false,
            val dayIndex: Int = 0,
            val dayCount: Int = 1,
        ) : Row()
    }

    /** One calendar day of an instance: its slice of the stream. */
    private class Segment(
        val event: EventInstance,
        val day: Long,
        val begin: Long,
        val end: Long,
        val index: Int,
        val count: Int,
    )

    /**
     * Builds the stream: ended events dropped, remaining sorted (all-day first
     * within each day), day separators inserted, and the upcoming event(s)
     * flagged per [allDayHighlight] (see [AllDayHighlight]).
     *
     * A multi-day event (a trip, a three-day all-day conference) appears on every
     * day it covers — the days already over are dropped, continuation days
     * carried past [horizonDays] from today are cut (an event's FIRST day is
     * never cut: the feed decided it belongs). A continuation row sorts first
     * among that day's timed events: it started before any of them.
     */
    fun rows(
        events: List<EventInstance>,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        allDayHighlight: AllDayHighlight = AllDayHighlight.BOTH,
        horizonDays: Int = Int.MAX_VALUE,
    ): List<Row> {
        val todayStart = dayStart(now, zone)
        val horizonEnd =
            if (horizonDays == Int.MAX_VALUE) Long.MAX_VALUE else plusDays(todayStart, horizonDays, zone)
        val live = events
            .map {
                if (it.allDay) {
                    it.copy(begin = allDayToLocal(it.begin, zone), providerBegin = it.begin)
                } else it
            }
            .flatMap { segments(it, zone) }
            .filter { it.end > now && (it.index == 0 || it.day < horizonEnd) }
            .sortedWith(compareBy({ it.day }, { !it.event.allDay }, { it.begin }))
        if (live.isEmpty()) return emptyList()

        val nextTimed = live.firstOrNull { !it.event.allDay }
        val allDayToday = live.filter { it.event.allDay && it.day == todayStart }
        // Safety net: if there's neither a timed event nor a today all-day one to
        // feature, fall back to the very first upcoming row so something is always
        // highlighted (e.g. only a future, non-today all-day event is left).
        val fallback = if (nextTimed == null && allDayToday.isEmpty()) live.first() else null
        val smartAllDayWins = allDayHighlight == AllDayHighlight.SMART &&
            allDayToday.isNotEmpty() &&
            (nextTimed == null || nextTimed.begin - now >= SMART_TIMED_THRESHOLD_MS)
        val smartWinner = if (smartAllDayWins) allDayToday.first() else (nextTimed ?: fallback)

        val tomorrowStart = plusDays(todayStart, 1, zone)
        val result = ArrayList<Row>(live.size + 4)
        var lastDay = todayStart
        for (segment in live) {
            val day = segment.day
            if (day != lastDay) {
                val kind = if (day == tomorrowStart) DayKind.TOMORROW else DayKind.LATER
                result.add(Row.DayHeader(kind, day))
                lastDay = day
            }
            val next = when (allDayHighlight) {
                AllDayHighlight.BOTH -> segment === nextTimed || segment === fallback ||
                    segment in allDayToday
                AllDayHighlight.DISTINCT -> segment === nextTimed || segment === fallback
                AllDayHighlight.SMART -> segment === smartWinner
            }
            result.add(
                Row.Event(
                    segment.event,
                    next = next,
                    ongoing = !segment.event.allDay && segment.begin <= now,
                    allDayFeatured = allDayHighlight == AllDayHighlight.DISTINCT &&
                        segment in allDayToday,
                    dayIndex = segment.index,
                    dayCount = segment.count,
                )
            )
        }
        return result
    }

    /**
     * Splits an instance (all-day ones already local-anchored) into its days.
     * All-day: the provider's end is the UTC midnight AFTER the last day. Timed:
     * an event ending exactly at midnight ends on the previous day.
     */
    private fun segments(event: EventInstance, zone: TimeZone): List<Segment> {
        val firstDay = dayStart(event.begin, zone)
        val end = if (event.allDay) allDayToLocal(event.end, zone) else event.end
        val lastDay = if (end > event.begin) dayStart(end - 1, zone) else firstDay
        val days = ArrayList<Long>()
        var day = firstDay
        while (day <= lastDay && days.size < MAX_SEGMENT_DAYS) {
            days.add(day)
            day = plusDays(day, 1, zone)
        }
        val count = days.size
        return days.mapIndexed { index, d ->
            val dayEnd = plusDays(d, 1, zone)
            Segment(
                event, d,
                begin = if (index == 0) event.begin else d,
                end = if (index == count - 1 && !event.allDay) end else dayEnd,
                index = index, count = count,
            )
        }
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

    /** Hard stop for runaway spans (a years-long "event" must not flood the stream). */
    private const val MAX_SEGMENT_DAYS = 62

    /** [AllDayHighlight.SMART]: all-day only outranks a timed event this far off. */
    private const val SMART_TIMED_THRESHOLD_MS = 3 * 60 * 60_000L
}
