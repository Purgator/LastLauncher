package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.calendar.Agenda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AgendaTest {

    private val zone = TimeZone.getTimeZone("Europe/Paris")

    /** 2026-07-14 (a Tuesday) at [hour]:[minute] Paris time, plus [dayOffset] days. */
    private fun at(hour: Int, minute: Int = 0, dayOffset: Int = 0): Long {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(2026, Calendar.JULY, 14, hour, minute)
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return cal.timeInMillis
    }

    private fun event(
        begin: Long, end: Long = begin + 3_600_000, title: String = "e",
        allDay: Boolean = false, id: Long = begin,
    ) = Agenda.EventInstance(id, begin, end, title, "", allDay, 1)

    private fun events(rows: List<Agenda.Row>) = rows.filterIsInstance<Agenda.Row.Event>()

    /** UTC midnight of 2026-07-[day] — how the provider stores all-day instances. */
    private fun utcMidnight(day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JULY, day, 0, 0)
        }.timeInMillis

    @Test
    fun endedEventsAreDroppedAndNextIsFlagged() {
        val now = at(10, 0)
        val rows = Agenda.rows(
            listOf(
                event(at(8), end = at(9), title = "past"),
                event(at(14), title = "next"),
                event(at(19), title = "later"),
            ),
            now, zone
        )
        assertEquals(listOf("next", "later"), events(rows).map { it.event.title })
        assertEquals(listOf(true, false), events(rows).map { it.next })
    }

    @Test
    fun ongoingEventStaysAndCountsAsNext() {
        val now = at(10, 30)
        val rows = Agenda.rows(
            listOf(event(at(10), end = at(11), title = "meeting")), now, zone
        )
        val row = events(rows).single()
        assertTrue(row.ongoing)
        assertTrue(row.next)
    }

    @Test
    fun dayHeadersSeparateDaysWithTomorrowKind() {
        val now = at(10)
        val rows = Agenda.rows(
            listOf(
                event(at(14), title = "today"),
                event(at(9, dayOffset = 1), title = "tomorrow"),
                event(at(9, dayOffset = 3), title = "friday"),
            ),
            now, zone
        )
        val headers = rows.filterIsInstance<Agenda.Row.DayHeader>()
        assertEquals(2, headers.size)
        assertEquals(Agenda.DayKind.TOMORROW, headers[0].kind)
        assertEquals(Agenda.DayKind.LATER, headers[1].kind)
        // No header before today's events.
        assertTrue(rows.first() is Agenda.Row.Event)
    }

    @Test
    fun allDayEventsAreNormalizedFromUtcAndSortFirst() {
        val now = at(8)
        // The provider stores all-day instances as UTC midnight of the date.
        val utcMidnightTomorrow = utcMidnight(15)
        val rows = Agenda.rows(
            listOf(
                event(at(9, dayOffset = 1), title = "timed"),
                event(
                    utcMidnightTomorrow, end = utcMidnightTomorrow + 86_400_000,
                    title = "birthday", allDay = true
                ),
            ),
            now, zone
        )
        // One header (tomorrow), then the all-day event before the timed one.
        assertEquals(Agenda.DayKind.TOMORROW, (rows[0] as Agenda.Row.DayHeader).kind)
        assertEquals(listOf("birthday", "timed"), events(rows).map { it.event.title })
        // Tomorrow's all-day event isn't today's, so only the timed event is "next".
        assertEquals(listOf(false, true), events(rows).map { it.next })
    }

    @Test
    fun todayAllDayEventIsStillShownAndPastAllDayIsNot() {
        val now = at(13)
        val rows = Agenda.rows(
            listOf(
                event(utcMidnight(13), title = "yesterday", allDay = true),
                event(utcMidnight(14), title = "today", allDay = true),
            ),
            now, zone
        )
        assertEquals(listOf("today"), events(rows).map { it.event.title })
    }

    @Test
    fun bothModeHighlightsTodaysAllDayAlongsideNextTimed() {
        val now = at(8)
        val rows = Agenda.rows(
            listOf(
                event(utcMidnight(14), title = "birthday", allDay = true),
                event(at(14), title = "meeting"),
            ),
            now, zone, allDayHighlight = Agenda.AllDayHighlight.BOTH,
        )
        assertEquals(listOf("birthday", "meeting"), events(rows).map { it.event.title })
        assertEquals(listOf(true, true), events(rows).map { it.next })
    }

    @Test
    fun distinctModeMarksAllDayFeaturedSeparatelyFromNext() {
        val now = at(8)
        val rows = Agenda.rows(
            listOf(
                event(utcMidnight(14), title = "birthday", allDay = true),
                event(at(14), title = "meeting"),
            ),
            now, zone, allDayHighlight = Agenda.AllDayHighlight.DISTINCT,
        )
        val (birthday, meeting) = events(rows)
        assertTrue(birthday.allDayFeatured)
        assertEquals(false, birthday.next)
        assertTrue(meeting.next)
        assertEquals(false, meeting.allDayFeatured)
    }

    @Test
    fun smartModeLetsAllDayWinWhenTimedEventIsFarOff() {
        val now = at(8)
        val rows = Agenda.rows(
            listOf(
                event(utcMidnight(14), title = "birthday", allDay = true),
                event(at(20), title = "dinner"), // 12h away
            ),
            now, zone, allDayHighlight = Agenda.AllDayHighlight.SMART,
        )
        assertEquals(listOf(true, false), events(rows).map { it.next })
    }

    @Test
    fun smartModeLetsTimedWinWhenItIsSoon() {
        val now = at(8)
        val rows = Agenda.rows(
            listOf(
                event(utcMidnight(14), title = "birthday", allDay = true),
                event(at(9), title = "standup"), // 1h away
            ),
            now, zone, allDayHighlight = Agenda.AllDayHighlight.SMART,
        )
        assertEquals(listOf(false, true), events(rows).map { it.next })
    }

    @Test
    fun minutesUntilRoundsUpAndClampsAtZero() {
        val now = at(10)
        assertEquals(0, Agenda.minutesUntil(now - 5_000, now))
        assertEquals(1, Agenda.minutesUntil(now + 1_000, now))
        assertEquals(30, Agenda.minutesUntil(now + 30 * 60_000, now))
        assertEquals(90, Agenda.minutesUntil(now + 90 * 60_000 - 1, now))
    }

    @Test
    fun emptyInputYieldsNoRows() {
        assertTrue(Agenda.rows(emptyList(), at(10), zone).isEmpty())
    }

    // ------------------------------------------------------------ multi-day

    @Test
    fun multiDayAllDayEventAppearsOnEveryDayItCovers() {
        // 14–16 July inclusive: the provider's end is the UTC midnight after the last day.
        val now = at(10)
        val rows = Agenda.rows(
            listOf(event(utcMidnight(14), end = utcMidnight(17), title = "trip", allDay = true)),
            now, zone
        )
        val shown = events(rows)
        assertEquals(listOf("trip", "trip", "trip"), shown.map { it.event.title })
        assertEquals(listOf(0, 1, 2), shown.map { it.dayIndex })
        assertEquals(listOf(3, 3, 3), shown.map { it.dayCount })
        // Today's slice is featured as a today all-day event; later days are not.
        assertEquals(listOf(true, false, false), shown.map { it.next })
        // One header per further day: tomorrow, then Thu 16.
        val headers = rows.filterIsInstance<Agenda.Row.DayHeader>()
        assertEquals(listOf(Agenda.DayKind.TOMORROW, Agenda.DayKind.LATER), headers.map { it.kind })
        assertEquals(listOf(at(0, dayOffset = 1), at(0, dayOffset = 2)), headers.map { it.dayStart })
    }

    @Test
    fun multiDayAllDayEventStillShowsOnItsLaterDays() {
        // Day 2 of the same trip: yesterday's slice is gone, today's is featured.
        val now = at(10, dayOffset = 1)
        val rows = Agenda.rows(
            listOf(event(utcMidnight(14), end = utcMidnight(17), title = "trip", allDay = true)),
            now, zone
        )
        val shown = events(rows)
        assertEquals(listOf(1, 2), shown.map { it.dayIndex })
        assertEquals(listOf(true, false), shown.map { it.next })
        assertTrue(rows.first() is Agenda.Row.Event) // today: no header
    }

    @Test
    fun multiDayTimedEventContinuesUnderTheNextDayAndIsOngoingOnlyToday() {
        // Fri-style overnight: 14 July 18:00 → 16 July 10:00.
        val now = at(20)
        val rows = Agenda.rows(
            listOf(
                event(at(18), end = at(10, dayOffset = 2), title = "festival"),
                event(at(9, dayOffset = 1), title = "standup"),
            ),
            now, zone
        )
        val shown = events(rows)
        assertEquals(listOf("festival", "festival", "standup", "festival"), shown.map { it.event.title })
        assertEquals(listOf(0, 1, 0, 2), shown.map { it.dayIndex })
        // Only the slice containing now is ongoing; the first slice is the "next" one.
        assertEquals(listOf(true, false, false, false), shown.map { it.ongoing })
        assertEquals(listOf(true, false, false, false), shown.map { it.next })
        // The instance itself is untouched on every row (view intents need it).
        assertTrue(shown.filter { it.event.title == "festival" }.all { it.event.begin == at(18) && it.event.end == at(10, dayOffset = 2) })
    }

    @Test
    fun timedEventEndingAtMidnightDoesNotSpillIntoTheNextDay() {
        val now = at(10)
        val rows = Agenda.rows(
            listOf(event(at(22), end = at(0, dayOffset = 1), title = "late")), now, zone
        )
        assertEquals(1, events(rows).size)
        assertEquals(1, events(rows).single().dayCount)
    }

    @Test
    fun continuationDaysAreCutAtTheHorizonButTheFirstDayNeverIs() {
        val now = at(10)
        val rows = Agenda.rows(
            listOf(
                event(utcMidnight(14), end = utcMidnight(24), title = "long", allDay = true),
                event(at(9, dayOffset = 5), title = "far"), // past the horizon, but a first day
            ),
            now, zone, horizonDays = 3
        )
        val shown = events(rows)
        assertEquals(listOf("long", "long", "long", "far"), shown.map { it.event.title })
        assertEquals(10, shown.first().dayCount) // the span itself is still known
    }
}
