package de.bewegungswelt.radar.locaboo

import com.sun.net.httpserver.HttpServer
import de.bewegungswelt.radar.availability.AvailabilityLoadException
import de.bewegungswelt.radar.availability.calculateAvailability
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.SlotStatus
import de.bewegungswelt.radar.model.TimeWindow
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocabooAvailabilitySourceTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC)
    private val range = CalendarRange(LocalDate.parse("2026-11-06"), LocalDate.parse("2026-11-09"))

    @Test
    fun `anonymous HTTP calendar maps custom openings occupancy and restrictions without event details`() = runBlocking {
        FixtureServer().use { server ->
            server.metadata = metadata(
                custom = """[{"location_id":13001,"day":"2026-11-07","from_hour":"12:00:00","to_hour":"16:00:00","customer_groups":[]}]""",
                lead = """[{"location_id":13001,"date":"2026-11-06","lead_time_from_hour":"15:00","lead_time_to_hour":"16:00"}]""",
                earliest = """{"13001":"2026-11-06 15:30:00"}""",
                latest = "2026-11-07",
            )
            server.intervals = """{
                "events":{"unexpected_personal_shape":{"ignored":"SYNTHETIC_PRIVATE_SENTINEL"}},
                "booked_locations":[
                    {"location_id":13001,"date":"2026-11-06","from_hour":"16:30:00","to_hour":"17:00:00"},
                    {"location_id":99999,"date":"2026-11-06","from_hour":"17:00:00","to_hour":"19:00:00"}
                ],
                "holidays":{"13001":[{"from":"2026-11-07 14:00:00","to":"2026-11-07 15:00:00","is_closing_time":true,"is_holiday":false,"description":"SYNTHETIC_PRIVATE_SENTINEL"}]},
                "price_badges":{"ignored":true}
            }"""
            val snapshot = server.source(clock).loadCalendar(range)
            assertEquals(range, snapshot.range)
            assertEquals(clock.instant(), snapshot.fetchedAt)
            assertEquals(Instant.parse("2026-11-07T23:00:00Z"), snapshot.bookingHorizonEndExclusive)
            assertEquals(3, snapshot.openingWindows.size)
            assertEquals(window("2026-11-07T11:00:00Z", "2026-11-07T15:00:00Z"), snapshot.openingWindows[1])
            assertEquals(listOf(window("2026-11-06T15:30:00Z", "2026-11-06T16:00:00Z")), snapshot.bookings)
            assertEquals(listOf(window("2026-11-07T13:00:00Z", "2026-11-07T14:00:00Z")), snapshot.closures)
            assertEquals(Duration.ofHours(1), snapshot.bookingRules!!.minimumDuration)
            val slots = calculateAvailability(range, snapshot, clock.instant()).associateBy { it.window.start }
            assertEquals(SlotStatus.CLOSED, slots.getValue(Instant.parse("2026-11-06T14:30:00Z")).status)
            assertEquals(SlotStatus.CLOSED, slots.getValue(Instant.parse("2026-11-06T15:00:00Z")).status) // only 30 minutes before booking
            assertEquals(SlotStatus.BOOKED, slots.getValue(Instant.parse("2026-11-06T15:30:00Z")).status)
            assertEquals(SlotStatus.BOOKABLE, slots.getValue(Instant.parse("2026-11-06T16:00:00Z")).status)
            assertEquals(SlotStatus.CLOSED, slots.getValue(Instant.parse("2026-11-07T13:00:00Z")).status)
            assertFalse(slots.values.any { it.window.start >= Instant.parse("2026-11-07T23:00:00Z") && it.status == SlotStatus.BOOKABLE })

            val eventRequest = server.requests.single { it.path == "/calendar/load_events" }
            val fields = eventRequest.body.split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals("2026-11-08", fields["to"])
            assertEquals("13001", fields["locations[]"])
            assertEquals("424", fields["platform_widget_id"])
            assertTrue(server.requests.all { it.path in ReadPaths })
            assertTrue(server.requests.filter { it.path != PagePath }.all { it.method == "POST" })
        }
    }

    @Test
    fun `short discovery load exposes full verified horizon independent of requested range`() = runBlocking {
        FixtureServer().use { server ->
            val initial = CalendarRange(range.start, range.start.plusDays(1))
            val snapshot = server.source(clock).loadCalendar(initial)
            assertEquals(initial, snapshot.range)
            assertEquals(Instant.parse("2027-04-01T22:00:00Z"), snapshot.bookingHorizonEndExclusive)
            // Inclusive venue-local max_end_dates becomes next local midnight, including DST.
            server.metadata = metadata(latest = "2027-03-28")
            assertEquals(Instant.parse("2027-03-28T22:00:00Z"),
                server.source(clock).loadCalendar(initial).bookingHorizonEndExclusive)
        }
    }

    @Test
    fun `empty polymorphic collections are valid and midnight closure covers full day`() = runBlocking {
        FixtureServer().use { server ->
            val source = server.source(clock)
            val empty = source.loadCalendar(range)
            assertTrue(empty.bookings.isEmpty())
            assertTrue(calculateAvailability(range, empty, clock.instant()).any { it.status == SlotStatus.BOOKABLE })
            server.intervals = """{"booked_locations":[],"holidays":{"13001":[
                {"from":"2026-11-06 00:00:00","to":"2026-11-06 23:59:59","is_closing_time":true,"is_holiday":true}
            ]}}"""
            val closed = source.loadCalendar(range)
            assertEquals(listOf(window("2026-11-05T23:00:00Z", "2026-11-06T23:00:00Z")), closed.closures)
            assertTrue(calculateAvailability(range, closed, clock.instant()).take(48).all { it.status == SlotStatus.CLOSED })
        }
    }

    @Test
    fun `missing malformed or restricted data never yields a usable snapshot or exposes response text`() {
        val invalid = listOf(
            """{"cal_locations":[{"id":13001}],"cal_meta":{}}""",
            metadata().replace("\"max_end_dates\":{\"13001\":\"2027-04-01\"}", "\"max_end_dates\":{}"),
            metadata().replace("\"from_hour\":\"15:00:00\"", "\"from_hour\":\"SYNTHETIC_PRIVATE_SENTINEL\""),
            metadata().replace("\"customer_groups\":[]", "\"customer_groups\":[123]"),
        )
        for (body in invalid) {
            FixtureServer().use { server ->
                server.metadata = body
                val error = assertThrows(AvailabilityLoadException::class.java) {
                    runBlocking { server.source(clock).loadCalendar(range) }
                }
                assertFalse(error.toString().contains("SYNTHETIC_PRIVATE_SENTINEL"))
                assertNull(error.cause)
            }
        }
        for (body in listOf("{}", """{"booked_locations":[],"holidays":[{"unexpected":true}]}""", """{"booked_locations":null,"holidays":[]}""")) {
            FixtureServer().use { server ->
                server.intervals = body
                assertThrows(AvailabilityLoadException::class.java) { runBlocking { server.source(clock).loadCalendar(range) } }
            }
        }
    }

    @Test
    fun `changed widget and denied booking configuration fail closed`() {
        FixtureServer().use { server ->
            server.page = Page.replace("half-hour", "full-hour")
            assertThrows(AvailabilityLoadException::class.java) { runBlocking { server.source(clock).loadCalendar(range) } }
            assertEquals(1, server.requests.size)
        }
        FixtureServer().use { server ->
            server.flags = """{"success":1,"flags":{"13001":{"direct_booking":0}}}"""
            assertThrows(AvailabilityLoadException::class.java) { runBlocking { server.source(clock).loadCalendar(range) } }
        }
    }

    @Test
    fun `transient read failure retries once but persistent HTTP failure has sanitized diagnostic`() = runBlocking {
        FixtureServer().use { server ->
            server.failedReads = 1
            assertTrue(server.source(clock).loadCalendar(range).openingWindows.isNotEmpty())
            assertEquals(2, server.requests.count { it.path == "/calendar/load_events" })
        }
        FixtureServer().use { server ->
            server.failedReads = 3
            val error = assertThrows(AvailabilityLoadException::class.java) { runBlocking { server.source(clock).loadCalendar(range) } }
            assertNull(error.cause)
            assertFalse(error.message!!.contains("SYNTHETIC_PRIVATE_SENTINEL"))
            assertEquals(2, server.requests.count { it.path == "/calendar/load_events" })
        }
    }

    @Test
    fun `offset-free DST gaps and overlaps fail closed rather than silently shifting bookings`() {
        for (date in listOf("2026-03-29", "2026-10-25")) {
            FixtureServer().use { server ->
                server.intervals = """{"booked_locations":[{"location_id":13001,"date":"$date","from_hour":"02:15:00","to_hour":"03:30:00"}],"holidays":[]}"""
                val day = LocalDate.parse(date)
                assertThrows(AvailabilityLoadException::class.java) {
                    runBlocking { server.source(clock).loadCalendar(CalendarRange(day, day.plusDays(1))) }
                }
            }
        }
    }

    @Test
    fun `cancellation stays cancellation instead of becoming a failed calendar`() = runBlocking {
        FixtureServer().use { server ->
            server.failedReads = 3
            var cancelled = false
            val job = launch {
                try { server.source(clock).loadCalendar(range) }
                catch (error: CancellationException) { cancelled = true; throw error }
            }
            withTimeout(5_000) {
                while (server.requests.none { it.path == "/calendar/load_events" }) delay(10)
            }
            job.cancelAndJoin()
            assertTrue(cancelled)
            assertTrue(job.isCancelled)
        }
    }

    private fun window(start: String, end: String) = TimeWindow(Instant.parse(start), Instant.parse(end))

    private class FixtureServer : AutoCloseable {
        var page = Page
        var metadata = metadata()
        var intervals = """{"booked_locations":[],"holidays":[]}"""
        var flags = """{"success":1,"flags":{"13001":{"direct_booking":2}}}"""
        var failedReads = 0
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests += RecordedRequest(path, exchange.requestMethod, exchange.requestBody.bufferedReader().use { it.readText() })
                val (status, text) = when {
                    path == PagePath -> { exchange.responseHeaders.add("Set-Cookie", "anonymous=test-only; Path=/; HttpOnly"); 200 to page }
                    exchange.requestHeaders.getFirst("Cookie") != "anonymous=test-only" -> 403 to "Missing session"
                    path == "/calendar/load_events" && failedReads-- > 0 -> 503 to "SYNTHETIC_PRIVATE_SENTINEL"
                    path == "/booking/direct_booking_flags" -> 200 to flags
                    path == "/de/calendar/load_locations" -> 200 to metadata
                    path == "/calendar/load_events" -> 200 to intervals
                    else -> 404 to "Unexpected endpoint"
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }

        fun source(clock: Clock) = LocabooAvailabilitySource("http://127.0.0.1:${server.address.port}/".toHttpUrl(), clock)
        override fun close() = server.stop(0)
    }

    private data class RecordedRequest(val path: String, val method: String, val body: String)

    private companion object {
        const val PagePath = "/de/bewegungsweltwaldau/privatpersonen"
        val ReadPaths = setOf(PagePath, "/booking/direct_booking_flags", "/de/calendar/load_locations", "/calendar/load_events")
        val Page = """<html>
            <input id="manager_id" value="12578"><input value='424' id='platform_widget_id'>
            <input id="allowed_location_array" value="13001"><input id="calender_snap_timing" value="half-hour">
            <input id="ignored_customer_field" value="SYNTHETIC_PRIVATE_SENTINEL">
        </html>"""

        fun metadata(custom: String = "[]", lead: String = "[]", earliest: String = "[]", latest: String = "2027-04-01") = """{
            "cal_locations":[{"id":"13001","ignored":"SYNTHETIC_PRIVATE_SENTINEL"}],
            "cal_meta":{
                "location_opentime":[
                    {"location_id":13001,"day":5,"from_hour":"15:00:00","to_hour":"19:00:00","customer_groups":[]},
                    {"location_id":13001,"day":6,"from_hour":"10:00:00","to_hour":"19:00:00","customer_groups":[]},
                    {"location_id":13001,"day":0,"from_hour":"10:00:00","to_hour":"19:00:00","customer_groups":[]},
                    {"location_id":99999,"day":1,"from_hour":"08:00:00","to_hour":"22:00:00","customer_groups":[]}
                ],"custom_days":$custom
            },"lead_times":$lead,"allowed_start_dates":$earliest,"max_end_dates":{"13001":"$latest"}
        }"""
    }
}
