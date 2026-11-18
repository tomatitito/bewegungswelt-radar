package de.bewegungswelt.radar

import android.content.Context
import android.content.SharedPreferences
import de.bewegungswelt.radar.availability.AvailabilityLoadException
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.SlotStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal enum class QueryTrigger { CALENDAR, BACKGROUND }
internal enum class QueryOutcome { SUCCESS, FAILURE, CANCELLED }
internal enum class QueryFailure { NETWORK, TIMEOUT, INVALID_DATA, INTERNAL }

internal data class QueryLogEntry(
    val startedAt: Instant,
    val finishedAt: Instant,
    val trigger: QueryTrigger,
    val range: CalendarRange,
    val batchCount: Int,
    val outcome: QueryOutcome,
    val failure: QueryFailure? = null,
    val bookableStarts: Int? = null,
    val bookedHalfHours: Int? = null,
    val bookingHorizonEndExclusive: Instant? = null,
) {
    init {
        require(finishedAt >= startedAt)
        require(batchCount >= 0)
        require((outcome == QueryOutcome.FAILURE) == (failure != null))
        require((outcome == QueryOutcome.SUCCESS) == (bookableStarts != null && bookedHalfHours != null))
    }

    val elapsed: Duration get() = Duration.between(startedAt, finishedAt)
}

internal fun interface QueryDiagnosticsRecorder {
    suspend fun record(entry: QueryLogEntry)

    companion object {
        val None = QueryDiagnosticsRecorder { }
    }
}

internal data class QueryAggregate(
    val bookableStarts: Int,
    val bookedHalfHours: Int,
    val bookingHorizonEndExclusive: Instant?,
)

internal fun queryAggregate(snapshot: CalendarSnapshot, now: Instant): QueryAggregate {
    val slots = de.bewegungswelt.radar.availability.calculateAvailability(snapshot.range, snapshot, now)
    var bookedHalfHours = 0
    var date = snapshot.range.start
    while (date < snapshot.range.endExclusive) {
        var start = date.atStartOfDay(snapshot.range.zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(snapshot.range.zone).toInstant()
        while (start < end) {
            val slotEnd = minOf(start.plusSeconds(30 * 60), end)
            if (snapshot.bookings.any { it.start < slotEnd && start < it.endExclusive }) bookedHalfHours++
            start = slotEnd
        }
        date = date.plusDays(1)
    }
    return QueryAggregate(
        bookableStarts = slots.count { it.status == SlotStatus.BOOKABLE },
        bookedHalfHours = bookedHalfHours,
        bookingHorizonEndExclusive = snapshot.bookingHorizonEndExclusive,
    )
}

internal fun queryFailure(error: Throwable): QueryFailure = when (error) {
    is TimeoutCancellationException -> QueryFailure.TIMEOUT
    is AvailabilityLoadException -> when {
        error.diagnostic.message == "Locaboo ist derzeit nicht erreichbar. Bitte erneut versuchen." -> QueryFailure.NETWORK
        error.diagnostic.message == "Locaboo-Kalenderdaten sind unvollständig oder haben sich geändert." -> QueryFailure.INVALID_DATA
        else -> QueryFailure.INTERNAL
    }
    is JSONException, is IllegalArgumentException, is IllegalStateException, is NoSuchElementException -> QueryFailure.INVALID_DATA
    else -> QueryFailure.INTERNAL
}

internal class QueryDiagnosticsStore(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) : QueryDiagnosticsRecorder, SharedPreferences.OnSharedPreferenceChangeListener {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val mutableEntries = MutableStateFlow(read(clock.instant()))
    val entries = mutableEntries.asStateFlow()

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)
        synchronized(Lock) {
            decodedEntries()?.let { stored ->
                val retained = retain(stored, clock.instant())
                if (retained != stored) {
                    check(preferences.edit().putString(EntriesKey, encode(retained).toString()).commit())
                    mutableEntries.value = retained
                }
            }
        }
    }

    override suspend fun record(entry: QueryLogEntry) = withContext(Dispatchers.IO) {
        synchronized(Lock) {
            val retained = retain(read(clock.instant()) + entry, clock.instant())
            check(preferences.edit().putString(EntriesKey, encode(retained).toString()).commit())
            mutableEntries.value = retained
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(Lock) {
            check(preferences.edit().remove(EntriesKey).commit())
            mutableEntries.value = emptyList()
        }
    }

    fun retainedEntries(): List<QueryLogEntry> = synchronized(Lock) {
        read(clock.instant()).also { mutableEntries.value = it }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == EntriesKey) mutableEntries.value = read(clock.instant())
    }

    private fun read(now: Instant): List<QueryLogEntry> =
        decodedEntries()?.let { retain(it, now) }.orEmpty()

    private fun decodedEntries(): List<QueryLogEntry>? {
        val text = preferences.getString(EntriesKey, null) ?: return emptyList()
        return try {
            val root = JSONObject(text)
            if (root.getInt("version") != 1) null else {
                val rows = root.getJSONArray("entries")
                buildList { repeat(rows.length()) { add(decode(rows.getJSONObject(it))) } }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun retain(entries: List<QueryLogEntry>, now: Instant): List<QueryLogEntry> = entries
        .filter { it.finishedAt >= now.minus(Retention) }
        .sortedWith(EntryOrder)
        .take(MaxEntries)

    private fun encode(entries: List<QueryLogEntry>) = JSONObject().put("version", 1).put(
        "entries", JSONArray().apply { entries.forEach { put(encode(it)) } },
    )

    private fun encode(entry: QueryLogEntry) = JSONObject()
        .put("started", entry.startedAt.toString())
        .put("finished", entry.finishedAt.toString())
        .put("trigger", entry.trigger.name)
        .put("startDate", entry.range.start.toString())
        .put("endDate", entry.range.endExclusive.toString())
        .put("zone", entry.range.zone.id)
        .put("batches", entry.batchCount)
        .put("outcome", entry.outcome.name)
        .apply {
            entry.failure?.let { put("failure", it.name) }
            entry.bookableStarts?.let { put("bookable", it) }
            entry.bookedHalfHours?.let { put("booked", it) }
            entry.bookingHorizonEndExclusive?.let { put("horizon", it.toString()) }
        }

    private fun decode(row: JSONObject) = QueryLogEntry(
        startedAt = Instant.parse(row.getString("started")),
        finishedAt = Instant.parse(row.getString("finished")),
        trigger = QueryTrigger.valueOf(row.getString("trigger")),
        range = CalendarRange(
            java.time.LocalDate.parse(row.getString("startDate")),
            java.time.LocalDate.parse(row.getString("endDate")),
            java.time.ZoneId.of(row.getString("zone")),
        ),
        batchCount = row.getInt("batches"),
        outcome = QueryOutcome.valueOf(row.getString("outcome")),
        failure = row.optString("failure").takeIf(String::isNotEmpty)?.let(QueryFailure::valueOf),
        bookableStarts = if (row.has("bookable")) row.getInt("bookable") else null,
        bookedHalfHours = if (row.has("booked")) row.getInt("booked") else null,
        bookingHorizonEndExclusive = row.optString("horizon").takeIf(String::isNotEmpty)?.let(Instant::parse),
    )

    internal companion object {
        const val PreferencesName = "query-diagnostics-v1"
        const val EntriesKey = "entries"
        const val MaxEntries = 200
        val Retention: Duration = Duration.ofDays(30)
        val Lock = Any()
        val EntryOrder = compareByDescending<QueryLogEntry> { it.startedAt }
            .thenByDescending { it.finishedAt }
            .thenBy { it.trigger.name }
    }
}

internal fun formatQueryDiagnostics(entries: List<QueryLogEntry>): String = buildString {
    appendLine("Bewegungswelt Radar · Diagnose")
    appendLine("Nur anonyme Abrufdaten · maximal 200 Einträge / 30 Tage")
    entries.forEach { entry ->
        append(DateTimeFormatter.ISO_INSTANT.format(entry.startedAt))
        append(" | ").append(entry.trigger.name)
        append(" | ").append(entry.range.start).append("..").append(entry.range.endExclusive)
        append(" | batches=").append(entry.batchCount)
        append(" | durationMs=").append(entry.elapsed.toMillis())
        append(" | ").append(entry.outcome.name)
        entry.failure?.let { append(":").append(it.name) }
        entry.bookableStarts?.let { append(" | bookableStarts=").append(it) }
        entry.bookedHalfHours?.let { append(" | bookedHalfHours=").append(it) }
        entry.bookingHorizonEndExclusive?.let { append(" | horizon=").append(it) }
        appendLine()
    }
}
