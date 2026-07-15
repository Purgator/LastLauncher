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
        val utcMidnightTomorrow = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JULY, 15, 0, 0)
        }.timeInMillis
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
        // The timed event carries the "next" flag, not the all-day one.
        assertEquals(listOf(false, true), events(rows).map { it.next })
    }

    @Test
    fun todayAllDayEventIsStillShownAndPastAllDayIsNot() {
        val now = at(13)
        fun utcMidnight(day: Int): Long =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(2026, Calendar.JULY, day, 0, 0)
            }.timeInMillis
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
}
