package de.bewegungswelt.radar

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.bewegungswelt.radar.availability.AvailabilitySource
import de.bewegungswelt.radar.availability.calculateNotificationAvailability
import de.bewegungswelt.radar.availability.compareAvailability
import de.bewegungswelt.radar.locaboo.LocabooAvailabilitySource
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.SlotStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class AvailabilityWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            withTimeout(Duration.ofMinutes(8).toMillis()) {
                monitorAvailability(
                    applicationContext,
                    LocabooAvailabilitySource(),
                    diagnostics = QueryDiagnosticsStore(applicationContext),
                )
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            failed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never expose exceptions, provider data, or raw responses in WorkManager output/logs.
            failed()
        }
    }

    private fun failed(): Result {
        MonitoringStore(applicationContext).preferences.edit().putBoolean("error", true).apply()
        return Result.retry()
    }
}

private val MonitoringZone = ZoneId.of("Europe/Berlin")

/** One complete scan is atomic: failed/incomplete scans do not advance either history or horizon. */
internal suspend fun monitorAvailability(
    context: Context,
    source: AvailabilitySource,
    clock: Clock = Clock.systemUTC(),
    diagnostics: QueryDiagnosticsRecorder = QueryDiagnosticsStore(context, clock),
) {
    val store = MonitoringStore(context)
    val settings: MonitoringSettings
    val historyText: String?
    synchronized(MonitoringStore.Lock) {
        settings = store.settings()
        historyText = store.preferences.getString("history", null)
    }
    if (!settings.enabled) return

    val startedAt = clock.instant()
    val today = startedAt.atZone(MonitoringZone).toLocalDate()
    var attemptedRange = CalendarRange(today, today.plusDays(1))
    var completedBatches = 0
    suspend fun record(entry: QueryLogEntry) {
        withContext(NonCancellable) { runCatching { diagnostics.record(entry) } }
    }

    try {
        ensureAvailabilityChannel(context)
        // Keep comparing while permission is blocked, but never accumulate a delivery backlog.
        val first = source.loadCalendar(attemptedRange)
        completedBatches++
        val horizon = checkNotNull(first.bookingHorizonEndExclusive)
        val rules = checkNotNull(first.bookingRules)
        val end = maxOf(today.plusDays(1), horizon.atZone(MonitoringZone).toLocalDate().let {
            if (it.atStartOfDay(MonitoringZone).toInstant() < horizon) it.plusDays(1) else it
        })
        attemptedRange = CalendarRange(today, end)
        // Fail closed for implausible upstream configuration; never silently truncate coverage.
        check(ChronoUnit.DAYS.between(today, end) <= 3660)
        val snapshots = mutableListOf(first)
        var next = first.range.endExclusive
        while (next < end) {
            val batchRange = CalendarRange(next, minOf(next.plusDays(62), end))
            val batch = source.loadCalendar(batchRange)
            completedBatches++
            check(batch.range == batchRange)
            check(batch.bookingHorizonEndExclusive == horizon && batch.bookingRules == rules)
            snapshots += batch
            next = batch.range.endExclusive
        }
        check(first.range == CalendarRange(today, today.plusDays(1)))
        // Merge coverage before interpretation so continuous bookings can cross batch boundaries.
        val complete = CalendarSnapshot(
            attemptedRange, snapshots.flatMap { it.openingWindows },
            snapshots.flatMap { it.bookings }, snapshots.flatMap { it.closures },
            snapshots.minOf { it.fetchedAt }, rules, snapshots.flatMap { it.unavailableWindows }, horizon,
        )
        val now = clock.instant()
        val observations = calculateNotificationAvailability(complete, now, Duration.ofMinutes(settings.minimumMinutes.toLong()))
        check(observations.none { it.status == SlotStatus.UNKNOWN })
        val signature = "${source.descriptor.id}|${rules.minimumDuration}|${rules.startInterval}|${settings.minimumMinutes}"
        val history = historyText?.let(::JSONObject)?.takeIf { it.optString("rules") == signature }
        val previous = mutableMapOf<Instant, SlotStatus>()
        history?.getJSONArray("starts")?.let { rows ->
            repeat(rows.length()) { index ->
                val row = rows.getJSONArray(index)
                val start = Instant.ofEpochSecond(row.getLong(0))
                if (start >= now) previous[start] = SlotStatus.valueOf(row.getString(1))
            }
        }
        // A verified horizon contraction confirms these previously observed starts are unavailable.
        previous.keys.filter { it >= horizon }.forEach { previous[it] = SlotStatus.CLOSED }
        val comparison = compareAvailability(
            observations, previous, history?.getString("horizon")?.let(Instant::parse), horizon,
        )
        val saved = JSONObject().put("rules", signature).put("horizon", horizon.toString())
            .put("starts", JSONArray().apply {
                comparison.confirmedStatuses.filterKeys { it >= now }.forEach { (start, status) ->
                    put(JSONArray().put(start.epochSecond).put(status.name))
                }
            })
        synchronized(MonitoringStore.Lock) {
            // Opt-out or a preference edit during network I/O invalidates comparison state only.
            if (store.settings() == settings && store.preferences.getString("history", null) == historyText) {
                check(store.preferences.edit().putString("history", saved.toString())
                    .putString("checkedAt", now.toString()).putBoolean("error", false).commit())
                // Persist before posting: retries/restarts cannot re-alert unchanged availability.
                if (notificationsAllowed(context)) postAvailability(
                    context,
                    comparison.newlyReleasedGroups,
                    comparison.reopenedGroups,
                    maxOf(settings.minimumMinutes, rules.minimumDuration.toMinutes().toInt()),
                )
            }
        }
        val aggregate = queryAggregate(complete, now)
        record(QueryLogEntry(
            startedAt, clock.instant(), QueryTrigger.BACKGROUND, attemptedRange, completedBatches,
            QueryOutcome.SUCCESS, bookableStarts = aggregate.bookableStarts,
            bookedHalfHours = aggregate.bookedHalfHours,
            bookingHorizonEndExclusive = horizon,
        ))
    } catch (timeout: TimeoutCancellationException) {
        record(QueryLogEntry(
            startedAt, clock.instant(), QueryTrigger.BACKGROUND, attemptedRange, completedBatches,
            QueryOutcome.FAILURE, failure = QueryFailure.TIMEOUT,
        ))
        throw timeout
    } catch (cancelled: CancellationException) {
        record(QueryLogEntry(
            startedAt, clock.instant(), QueryTrigger.BACKGROUND, attemptedRange, completedBatches,
            QueryOutcome.CANCELLED,
        ))
        throw cancelled
    } catch (failure: Exception) {
        record(QueryLogEntry(
            startedAt, clock.instant(), QueryTrigger.BACKGROUND, attemptedRange, completedBatches,
            QueryOutcome.FAILURE, failure = queryFailure(failure),
        ))
        throw failure
    }
}

internal fun postAvailability(
    context: Context,
    newlyReleasedGroups: List<List<Instant>>,
    reopenedGroups: List<List<Instant>>,
    minimumMinutes: Int,
) {
    val allStarts = newlyReleasedGroups.flatten() + reopenedGroups.flatten()
    val byDate = allStarts.groupBy { it.atZone(MonitoringZone).toLocalDate() }
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(MonitoringZone)
    val manager = notificationManager(context)
    fun ranges(groups: List<List<Instant>>, date: LocalDate) = groups
        .map { group -> group.filter { it.atZone(MonitoringZone).toLocalDate() == date } }
        .filter { it.isNotEmpty() }
        .joinToString("; ") { group ->
            if (group.size == 1) time.format(group.first())
            else "${time.format(group.first())}–${time.format(group.last())}"
        }
    // Large releases get one audible summary and at most seven date cards, not hundreds of alerts.
    byDate.keys.sorted().take(7).forEach { date ->
        val released = ranges(newlyReleasedGroups, date)
        val reopened = ranges(reopenedGroups, date)
        val changes = buildList {
            if (released.isNotEmpty()) add("Neu freigegebene Starts: $released")
            if (reopened.isNotEmpty()) add("Wieder freie Starts: $reopened")
        }.joinToString(" · ")
        val text = "$changes · mindestens $minimumMinutes Min. frei. Bitte erneut prüfen."
        val title = when {
            released.isNotEmpty() && reopened.isEmpty() -> "neu freigegebene Zeiten"
            released.isEmpty() -> "wieder freie Zeiten"
            else -> "neue freie Zeiten"
        }
        val notification = Notification.Builder(context, AvailabilityChannel)
            .setSmallIcon(R.drawable.ic_availability)
            .setContentTitle("${date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}: $title")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(availabilityIntent(context, date))
            .setAutoCancel(true)
            .setGroup("availability-dates")
            .setGroupAlertBehavior(if (byDate.size > 1) Notification.GROUP_ALERT_SUMMARY else Notification.GROUP_ALERT_CHILDREN)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        manager.notify(date.toString(), 1, notification)
    }
    if (byDate.size > 1) {
        manager.notify("availability-summary", 0, Notification.Builder(context, AvailabilityChannel)
            .setSmallIcon(R.drawable.ic_availability)
            .setContentTitle("Neue freie Zeiten an ${byDate.size} Tagen")
            .setContentText(if (byDate.size > 7) "Die ersten 7 Tage werden einzeln angezeigt. Weitere Termine im Kalender."
                else "Öffnen und aktuelle Verfügbarkeit prüfen")
            .setContentIntent(availabilityIntent(context, byDate.keys.min()))
            .setGroup("availability-dates").setGroupSummary(true)
            .setAutoCancel(true).build())
    }
}

internal fun availabilityIntent(context: Context, date: LocalDate): PendingIntent = PendingIntent.getActivity(
    context, 0,
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse("bewegungswelt://calendar/$date"))
        .putExtra("availabilityDate", date.toString())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
