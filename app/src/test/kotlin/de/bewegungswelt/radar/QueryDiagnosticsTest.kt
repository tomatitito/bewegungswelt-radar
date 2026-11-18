package de.bewegungswelt.radar

import android.content.Context
import de.bewegungswelt.radar.model.CalendarRange
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueryDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Europe/Berlin")
    private val base = Instant.parse("2026-11-30T12:00:00Z")
    private val clock = MutableClock(base, zone)
    private val preferences get() = context.getSharedPreferences(QueryDiagnosticsStore.PreferencesName, Context.MODE_PRIVATE)

    @Before fun reset() {
        preferences.edit().clear().commit()
        clock.now = base
    }

    @Test fun `store bounds entries by age count and deterministic newest-first order`() = runBlocking {
        val store = QueryDiagnosticsStore(context, clock)
        store.record(success(base.minusSeconds(31 * 86_400L), 999))
        repeat(205) { index ->
            val instant = base.minusSeconds(index.toLong())
            store.record(success(instant, index))
        }
        val entries = store.retainedEntries()
        assertEquals(200, entries.size)
        assertEquals(base, entries.first().startedAt)
        assertEquals(base.minusSeconds(199), entries.last().startedAt)
        assertFalse(entries.any { it.batchCount == 999 })
    }

    @Test fun `corruption fails closed remains clearable and does not remove unrelated values`() = runBlocking {
        preferences.edit().putString(QueryDiagnosticsStore.EntriesKey, "raw private exception text {broken")
            .putString("unrelated", "keep").commit()
        val store = QueryDiagnosticsStore(context, clock)
        assertTrue(store.retainedEntries().isEmpty())
        store.clear()
        assertEquals("keep", preferences.getString("unrelated", null))
        assertFalse(preferences.contains(QueryDiagnosticsStore.EntriesKey))
    }

    @Test fun `concurrent writes are serialized and failure persistence contains no throwable text`() = runBlocking {
        val foreground = QueryDiagnosticsStore(context, clock)
        val background = QueryDiagnosticsStore(context, clock)
        (0 until 40).map { index ->
            async(Dispatchers.Default) {
                val instant = base.plusSeconds(index.toLong())
                (if (index % 2 == 0) foreground else background).record(if (index == 0) QueryLogEntry(
                    instant, instant, QueryTrigger.CALENDAR, range, 0, QueryOutcome.FAILURE,
                    failure = queryFailure(IllegalStateException("secret endpoint response")),
                ) else success(instant, index))
            }
        }.awaitAll()
        assertEquals(40, foreground.retainedEntries().size)
        val raw = preferences.getString(QueryDiagnosticsStore.EntriesKey, null).orEmpty()
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("endpoint"))
        assertTrue(raw.contains("INVALID_DATA"))
    }

    private fun success(instant: Instant, batchCount: Int) = QueryLogEntry(
        instant, instant, QueryTrigger.BACKGROUND, range, batchCount, QueryOutcome.SUCCESS,
        bookableStarts = 3, bookedHalfHours = 5,
    )

    private val range = CalendarRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), zone)
}

private class MutableClock(var now: Instant, private val clockZone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = clockZone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}
