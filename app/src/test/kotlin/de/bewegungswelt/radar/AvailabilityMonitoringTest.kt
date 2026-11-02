package de.bewegungswelt.radar

import android.app.Notification
import android.content.Context
import de.bewegungswelt.radar.availability.AvailabilitySource
import de.bewegungswelt.radar.model.BookingRules
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.SourceDescriptor
import de.bewegungswelt.radar.model.TimeWindow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AvailabilityMonitoringTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val fetchedAt = Instant.parse("2026-11-02T06:00:00Z")
    private val now = Clock.fixed(fetchedAt, ZoneId.of("Europe/Berlin"))
    private val today = LocalDate.of(2026, 11, 2)
    private val zone = ZoneId.of("Europe/Berlin")
    private val store get() = MonitoringStore(context)
    private val manager get() = notificationManager(context)

    @Before fun reset() {
        store.preferences.edit().clear().commit()
        manager.cancelAll()
        ensureAvailabilityChannel(context)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        store.save(MonitoringSettings(enabled = true))
    }

    private inner class Source : AvailabilitySource {
        override val descriptor = SourceDescriptor("test", "Anonymous venue")
        var horizonDate = today.plusDays(130)
        var booking = true
        var failAfterDiscovery = false
        var unknown = false
        var overnight = false
        var duringLoad: (() -> Unit)? = null
        val ranges = mutableListOf<CalendarRange>()
        override suspend fun loadCalendar(range: CalendarRange): CalendarSnapshot {
            ranges += range
            check(Duration.between(range.start.atStartOfDay(zone), range.endExclusive.atStartOfDay(zone)).toDays() <= 62)
            if (failAfterDiscovery && range.start != today) error("sanitized fixture failure")
            duringLoad?.invoke()
            val dates = range.start.datesUntil(range.endExclusive).toList()
            val openings = if (overnight) dates.flatMap {
                listOf(window(it, 0, 1), TimeWindow(it.atTime(23, 0).atZone(zone).toInstant(),
                    it.plusDays(1).atStartOfDay(zone).toInstant()))
            } else dates.map { window(it, 9, 12) }
            return CalendarSnapshot(range, openings,
                if (booking) dates.map {
                    if (overnight) TimeWindow(it.atTime(23, 0).atZone(zone).toInstant(), it.plusDays(1).atStartOfDay(zone).toInstant())
                    else window(it, 9, 10)
                } else emptyList(), emptyList(), fetchedAt,
                if (unknown) null else BookingRules(Duration.ofMinutes(60), Duration.ofMinutes(30)),
                bookingHorizonEndExclusive = horizonDate.atStartOfDay(zone).toInstant())
        }
    }

    private fun window(date: LocalDate, from: Int, to: Int) = TimeWindow(
        date.atTime(from, 0).atZone(zone).toInstant(), date.atTime(to, 0).atZone(zone).toInstant())

    @Test fun `full horizon baseline stays silent and release beyond 60 days alerts once across restart`() = runBlocking {
        val source = Source()
        monitorAvailability(context, source, now)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(today.plusDays(130), source.ranges.last().endExclusive)
        source.horizonDate = today.plusDays(131)
        monitorAvailability(context, source, now)
        val notifications = manager.activeNotifications
        assertEquals(1, notifications.size)
        assertEquals(today.plusDays(130).toString(), notifications.single().tag)
        assertTrue(notifications.single().notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("10:00–11:00"))
        val intent = shadowOf(notifications.single().notification.contentIntent).savedIntent
        assertEquals(today.plusDays(130).toString(), intent.getStringExtra("availabilityDate"))
        manager.cancelAll()
        // Entirely fresh source/store instances read only persisted comparison state.
        val restarted = Source().apply { horizonDate = source.horizonDate }
        monitorAvailability(context, restarted, now)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `failed and unknown scans preserve baseline and release boundary then recovery alerts only real changes`() = runBlocking {
        val source = Source().apply { horizonDate = today.plusDays(2) }
        monitorAvailability(context, source, now)
        val baseline = store.preferences.getString("history", null)
        source.horizonDate = today.plusDays(3)
        source.failAfterDiscovery = true
        try { monitorAvailability(context, source, now); fail("Expected failed scan") } catch (_: IllegalStateException) { }
        assertEquals(baseline, store.preferences.getString("history", null))
        source.failAfterDiscovery = false
        source.unknown = true
        try { monitorAvailability(context, source, now); fail("Expected unknown scan") } catch (_: IllegalStateException) { }
        assertEquals(baseline, store.preferences.getString("history", null))
        source.unknown = false
        monitorAvailability(context, source, now)
        assertEquals(listOf(today.plusDays(2).toString()), manager.activeNotifications.map { it.tag })
        manager.cancelAll()
        monitorAvailability(context, source, now)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `cancellation groups new starts per date and a later rebooking permits another alert`() = runBlocking {
        val source = Source().apply { horizonDate = today.plusDays(1) }
        monitorAvailability(context, source, now)
        source.booking = false
        monitorAvailability(context, source, now)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("09:00–09:30"))
        manager.cancelAll()
        monitorAvailability(context, source, now)
        assertTrue(manager.activeNotifications.isEmpty())
        source.booking = true
        monitorAvailability(context, source, now)
        source.booking = false
        monitorAvailability(context, source, now)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun `duration change baselines silently cadence change preserves history and opt out invalidates inflight scan`() = runBlocking {
        val source = Source().apply { horizonDate = today.plusDays(1) }
        monitorAvailability(context, source, now)
        val history = store.preferences.getString("history", null)
        store.save(store.settings().copy(cadenceMinutes = 120))
        assertEquals(history, store.preferences.getString("history", null))
        store.save(store.settings().copy(minimumMinutes = 120))
        source.booking = false
        monitorAvailability(context, source, now)
        assertTrue(manager.activeNotifications.isEmpty())
        source.duringLoad = { store.save(store.settings().copy(enabled = false)) }
        monitorAvailability(context, source, now)
        assertNull(store.preferences.getString("history", null))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `blocked notifications do not build a backlog or break monitoring`() = runBlocking {
        val source = Source().apply { horizonDate = today.plusDays(1) }
        monitorAvailability(context, source, now)
        shadowOf(manager).setNotificationsEnabled(false)
        source.booking = false
        monitorAvailability(context, source, now)
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(manager).setNotificationsEnabled(true)
        monitorAvailability(context, source, now)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `continuous duration crosses request boundary and horizon edge remains confirmed closed`() = runBlocking {
        store.save(store.settings().copy(minimumMinutes = 120))
        val source = Source().apply { horizonDate = today.plusDays(2); overnight = true }
        monitorAvailability(context, source, now)
        assertTrue(manager.activeNotifications.isEmpty())
        source.booking = false
        monitorAvailability(context, source, now)
        val alert = manager.activeNotifications.single()
        assertEquals(today.toString(), alert.tag)
        assertTrue(alert.notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("Neue Starts: 23:00 · mindestens 120 Min."))
    }

    @Test fun `large releases have one audible summary and bounded date cards`() = runBlocking {
        val source = Source().apply { horizonDate = today.plusDays(1) }
        monitorAvailability(context, source, now)
        source.horizonDate = today.plusDays(20)
        monitorAvailability(context, source, now)
        assertEquals(8, manager.activeNotifications.size)
        val summary = manager.activeNotifications.single { it.tag == "availability-summary" }.notification
        assertEquals("Neue freie Zeiten an 19 Tagen", summary.extras.getString(Notification.EXTRA_TITLE))
        manager.activeNotifications.filter { it.tag != "availability-summary" }.forEach {
            assertEquals(Notification.GROUP_ALERT_SUMMARY, it.notification.groupAlertBehavior)
        }
    }
}
