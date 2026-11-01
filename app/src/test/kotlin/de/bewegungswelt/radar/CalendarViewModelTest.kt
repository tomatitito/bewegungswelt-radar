package de.bewegungswelt.radar

import de.bewegungswelt.radar.availability.AvailabilityLoadException
import de.bewegungswelt.radar.availability.AvailabilitySource
import de.bewegungswelt.radar.model.BookingRules
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.SlotStatus
import de.bewegungswelt.radar.model.SourceDescriptor
import de.bewegungswelt.radar.model.SourceDiagnostic
import de.bewegungswelt.radar.model.TimeWindow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val range = CalendarRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))
    private val now = Instant.parse("2026-11-01T10:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `successful load interprets real windows and a failed refresh removes green starts until recovery`() = runTest(dispatcher) {
        var response = CompletableDeferred<CalendarSnapshot>()
        val model = CalendarViewModel(source { response.await() }, Clock.fixed(now, range.zone))
        model.showRange(range)
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.slots.isEmpty())
        response.complete(snapshot(range))
        val loaded = model.state.first { it.confirmed }
        val nine = range.start.atTime(9, 0).atZone(range.zone).toInstant()
        assertEquals(SlotStatus.BOOKABLE, loaded.slots.first { it.window.start == nine }.status)
        assertEquals(SlotStatus.CLOSED, loaded.slots.first { it.window.start == nine.plusSeconds(1800) }.status)
        assertEquals(SlotStatus.BOOKED, loaded.slots.first { it.window.start == nine.plusSeconds(3600) }.status)

        response = CompletableDeferred()
        model.refresh()
        assertFalse(model.state.value.confirmed)
        assertTrue(model.state.value.slots.isEmpty())
        response.completeExceptionally(AvailabilityLoadException(SourceDiagnostic("Locaboo ist nicht erreichbar.")))
        val failed = model.state.first { !it.loading }
        assertFalse(failed.confirmed)
        assertEquals(now, failed.snapshot?.fetchedAt)
        assertEquals("Locaboo ist nicht erreichbar.", failed.diagnostic?.message)
        assertTrue(failed.slots.isEmpty())

        response = CompletableDeferred()
        model.refresh()
        response.complete(snapshot(range).copy(bookings = emptyList()))
        val recovered = model.state.first { it.confirmed }
        assertEquals(SlotStatus.BOOKABLE, recovered.slots.first { it.window.start == nine.plusSeconds(3600) }.status)
        assertNull(recovered.diagnostic)
    }

    @Test
    fun `obsolete failed requests cannot replace the newly navigated calendar`() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<CalendarSnapshot>()
        val newerRange = CalendarRange(range.start.plusMonths(1), range.endExclusive.plusMonths(1))
        val model = CalendarViewModel(source { requested ->
            if (requested == range) withContext(NonCancellable) { oldResponse.await() }
            else snapshot(requested)
        }, Clock.fixed(now, range.zone))
        model.showRange(range)
        runCurrent()
        model.showRange(newerRange)
        assertNull(model.state.value.snapshot)
        val newer = model.state.first { it.confirmed }
        oldResponse.completeExceptionally(AvailabilityLoadException(SourceDiagnostic("Alter Fehler")))
        runCurrent()
        assertEquals(newer, model.state.value)
        assertEquals(newerRange, model.state.value.range)
        assertTrue(model.state.value.slots.any { it.status == SlotStatus.BOOKABLE })
    }

    @Test
    fun `unexpected errors are sanitized and a first load failure has no confirmed availability`() = runTest(dispatcher) {
        val model = CalendarViewModel(source { error("private response contents") })
        model.showRange(range)
        val failed = model.state.first { !it.loading }
        assertFalse(failed.confirmed)
        assertNull(failed.snapshot)
        assertTrue(failed.slots.isEmpty())
        assertEquals("Verfügbarkeit konnte nicht geladen werden.", failed.diagnostic?.message)
    }

    @Test
    fun `foreground time updates expire starts without another fetch`() = runTest(dispatcher) {
        var currentTime = now
        val clock = object : Clock() {
            override fun getZone(): ZoneId = range.zone
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentTime, zone)
            override fun instant(): Instant = currentTime
        }
        val model = CalendarViewModel(source { snapshot(it).copy(fetchedAt = currentTime) }, clock)
        model.showRange(range)
        val loaded = model.state.first { it.confirmed }
        val nine = range.start.atTime(9, 0).atZone(range.zone).toInstant()
        assertEquals(SlotStatus.BOOKABLE, loaded.slots.first { it.window.start == nine }.status)
        currentTime = nine.plusSeconds(1)
        model.updateTime()
        val expired = model.state.first { it.slots.first { slot -> slot.window.start == nine }.status == SlotStatus.CLOSED }
        assertEquals(now, expired.snapshot?.fetchedAt)
    }

    private fun source(load: suspend (CalendarRange) -> CalendarSnapshot) = object : AvailabilitySource {
        override val descriptor = SourceDescriptor("test", "Sanitized calendar")
        override suspend fun loadCalendar(range: CalendarRange) = load(range)
    }

    private fun snapshot(range: CalendarRange): CalendarSnapshot {
        val nine = range.start.atTime(9, 0).atZone(range.zone).toInstant()
        return CalendarSnapshot(range, listOf(TimeWindow(nine, nine.plusSeconds(9000))),
            listOf(TimeWindow(nine.plusSeconds(3600), nine.plusSeconds(5400))), emptyList(), now,
            BookingRules(Duration.ofMinutes(60), Duration.ofMinutes(30)))
    }
}
