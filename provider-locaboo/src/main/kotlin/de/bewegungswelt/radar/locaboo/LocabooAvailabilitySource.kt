package de.bewegungswelt.radar.locaboo

import de.bewegungswelt.radar.availability.AvailabilityLoadException
import de.bewegungswelt.radar.availability.AvailabilitySource
import de.bewegungswelt.radar.model.BookingRules
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.SourceDescriptor
import de.bewegungswelt.radar.model.SourceDiagnostic
import de.bewegungswelt.radar.model.TimeWindow
import java.io.IOException
import java.io.Reader
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Anonymous, read-only access to the private-person calendar. No cart or draft endpoints. */
class LocabooAvailabilitySource internal constructor(
    private val baseUrl: HttpUrl,
    private val clock: Clock,
) : AvailabilitySource {
    constructor(clock: Clock = Clock.systemUTC()) : this("https://booking.locaboo.com/".toHttpUrl(), clock)

    override val descriptor = SourceDescriptor("locaboo-bewegungswelt-privat", "Bewegungswelt Waldau")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(AnonymousCookies())
        .build()

    override suspend fun loadCalendar(range: CalendarRange): CalendarSnapshot {
        require(range.zone == VenueZone) { "Locaboo calendar uses Europe/Berlin" }
        require(ChronoUnit.DAYS.between(range.start, range.endExclusive) <= 62) { "Load at most 62 days at once" }
        try {
            read("/de/bewegungsweltwaldau/privatpersonen") { input -> validatePage(input) }
            val flags = read("/booking/direct_booking_flags", form("manager_id" to ManagerId), LocabooResponses::flags)
            require(flags[ResourceId] in setOf("1", "2"))
            val metadata = read(
                "/de/calendar/load_locations",
                """{"manager_id":"$ManagerId","booking_mode":"true","widget_mode":"true","category":"","locations":[$ResourceId],"platform_resource_part_id":null,"platform_resource_id":null}"""
                    .toRequestBody("text/plain".toMediaType()),
                LocabooResponses::metadata,
            )
            require(metadata.resources.any { it["id"] == ResourceId })
            val intervals = read(
                "/calendar/load_events",
                form(
                    "from" to range.start.toString(), "to" to range.endExclusive.minusDays(1).toString(),
                    "mode" to "Week_single", "date" to range.start.toString(), "manager_id" to ManagerId,
                    "season_mode" to "live", "widget" to "true", "locations[]" to ResourceId,
                    "platform_widget_id" to WidgetId,
                ),
                LocabooResponses::intervals,
            )
            return snapshot(range, metadata, intervals)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            throw AvailabilityLoadException(SourceDiagnostic("Locaboo ist derzeit nicht erreichbar. Bitte erneut versuchen."))
        } catch (_: DateTimeException) {
            throw invalidData()
        } catch (_: IllegalArgumentException) {
            throw invalidData()
        } catch (_: IllegalStateException) {
            throw invalidData()
        } catch (_: NoSuchElementException) {
            throw invalidData()
        }
    }

    private fun snapshot(
        range: CalendarRange,
        metadata: LocabooResponses.Metadata,
        intervals: LocabooResponses.Intervals,
    ): CalendarSnapshot {
        fun List<Map<String, String>>.target() = filter { it.getValue("location_id") == ResourceId }
        val regular = metadata.opening.target()
        val custom = metadata.custom.target()
        require(regular.isNotEmpty() || custom.isNotEmpty())
        (regular + custom).forEach { require(it.getValue("customer_groups") == "false") }
        val weekly = regular.groupBy { it.getValue("day").toInt().also { day -> require(day in 0..6) } }
        val overrides = custom.groupBy { LocalDate.parse(it.getValue("day")) }
        val opening = generateSequence(range.start) { it.plusDays(1) }.takeWhile { it < range.endExclusive }
            .flatMap { date ->
                (overrides[date] ?: weekly[date.dayOfWeek.value % 7].orEmpty()).asSequence().map { row ->
                    dayWindow(date, row.getValue("from_hour"), row.getValue("to_hour"))
                }
            }.toList()
        val booked = intervals.booked.target().map { row ->
            dayWindow(LocalDate.parse(row.getValue("date")), row.getValue("from_hour"), row.getValue("to_hour"))
        }
        val closures = intervals.holidays[ResourceId].orEmpty().mapNotNull { row ->
            val closed = row.getValue("is_closing_time").toBooleanStrict()
            row.getValue("is_holiday").toBooleanStrict()
            if (closed) TimeWindow(localInstant(row.getValue("from")), localInstant(row.getValue("to"), end = true))
            else null
        }
        val unavailable = metadata.lead.target().map { row ->
            dayWindow(LocalDate.parse(row.getValue("date")), row.getValue("lead_time_from_hour"), row.getValue("lead_time_to_hour"))
        }.toMutableList()
        val start = range.start.atStartOfDay(VenueZone).toInstant()
        val end = range.endExclusive.atStartOfDay(VenueZone).toInstant()
        val fetchedAt = clock.instant()
        val earliest = maxOf(fetchedAt, metadata.earliest[ResourceId]?.let { localInstant(it) } ?: fetchedAt)
        if (earliest > start) unavailable += TimeWindow(start, minOf(end, earliest))
        val latest = LocalDate.parse(metadata.latest.getValue(ResourceId)).plusDays(1).atStartOfDay(VenueZone).toInstant()
        if (latest < end) unavailable += TimeWindow(maxOf(start, latest), end)
        return CalendarSnapshot(
            range = range, openingWindows = opening, bookings = booked, closures = closures, fetchedAt = fetchedAt,
            // Published private-person offers start at 60 minutes; half-hour rows are possible START times.
            bookingRules = BookingRules(Duration.ofHours(1), Duration.ofMinutes(30)),
            unavailableWindows = unavailable,
            bookingHorizonEndExclusive = latest,
        )
    }

    private fun dayWindow(date: LocalDate, from: String, to: String): TimeWindow {
        val fromTime = LocalTime.parse(from)
        val end = if (to in setOf("00:00", "00:00:00", "24:00", "24:00:00", "23:59:59")) {
            date.plusDays(1).atStartOfDay()
        } else {
            date.atTime(LocalTime.parse(to))
        }
        return TimeWindow(resolve(date.atTime(fromTime)), resolve(end))
    }

    private fun localInstant(value: String, end: Boolean = false): Instant {
        val local = LocalDateTime.parse(value.replace(' ', 'T'))
        return resolve(if (end && local.toLocalTime() == LocalTime.of(23, 59, 59)) local.plusSeconds(1) else local)
    }

    private fun resolve(local: LocalDateTime): Instant {
        val offsets = VenueZone.rules.getValidOffsets(local)
        // Offset-free ambiguous/nonexistent upstream times cannot establish safe availability.
        require(offsets.size == 1)
        return local.toInstant(offsets.single())
    }

    private fun validatePage(input: Reader) {
        val expected = mapOf("manager_id" to ManagerId, "platform_widget_id" to WidgetId,
            "allowed_location_array" to ResourceId, "calender_snap_timing" to "half-hour")
        val found = mutableMapOf<String, String>()
        input.buffered().lineSequence().forEach { line ->
            InputTag.findAll(line).forEach { tag ->
                val id = IdAttribute.find(tag.value)?.groupValues?.get(2)
                if (id in expected) {
                    val value = ValueAttribute.find(tag.value)?.groupValues?.get(2)
                    require(value == expected[id])
                    found[id!!] = requireNotNull(value)
                }
            }
        }
        require(found == expected)
    }

    /** Only known read requests retry, once, on transport failure or transient server errors. */
    private suspend fun <T> read(path: String, body: RequestBody? = null, decode: (Reader) -> T): T {
        val request = Request.Builder().url(requireNotNull(baseUrl.resolve(path)))
            .apply { if (body != null) post(body) }.build()
        repeat(2) { attempt ->
            try {
                return suspendCancellableCoroutine { continuation ->
                    val call = client.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            continuation.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use {
                                    if (it.code in setOf(502, 503, 504)) throw IOException("Temporary upstream failure")
                                    check(it.isSuccessful)
                                    decode(it.body.charStream())
                                }
                                continuation.resume(result)
                            } catch (error: Exception) {
                                continuation.resumeWithException(error)
                            }
                        }
                    })
                }
            } catch (error: IOException) {
                if (attempt == 1) throw error
                delay(500)
            }
        }
        error("Unreachable")
    }

    private fun form(vararg values: Pair<String, String>): RequestBody = FormBody.Builder().apply {
        values.forEach { (key, value) -> add(key, value) }
    }.build()

    private fun invalidData() = AvailabilityLoadException(
        SourceDiagnostic("Locaboo-Kalenderdaten sind unvollständig oder haben sich geändert."),
    )

    private companion object {
        const val ManagerId = "12578"
        const val WidgetId = "424"
        const val ResourceId = "13001"
        val VenueZone: ZoneId = ZoneId.of("Europe/Berlin")
        val InputTag = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
        val IdAttribute = Regex("""\bid\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
        val ValueAttribute = Regex("""\bvalue\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    }
}

/** Private to this anonymous client; never installs a process-wide or persistent cookie handler. */
private class AnonymousCookies : CookieJar {
    private var cookies = emptyList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies = (this.cookies.filterNot { old ->
            cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path }
        } + cookies).filter { it.expiresAt > System.currentTimeMillis() }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter {
        it.expiresAt > System.currentTimeMillis() && it.matches(url)
    }
}
