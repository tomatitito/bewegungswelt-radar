package de.bewegungswelt.radar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class MonitoringSettings(
    val enabled: Boolean = false,
    val minimumMinutes: Int = 60,
    val cadenceMinutes: Int = 60,
    val generation: String = "",
)

/** Settings and anonymous comparison state only; never stores snapshots or HTTP responses. */
internal class MonitoringStore(context: Context) {
    val preferences = context.getSharedPreferences("availability-monitor-v1", Context.MODE_PRIVATE)

    fun settings() = MonitoringSettings(
        preferences.getBoolean("enabled", false),
        preferences.getInt("minimum", 60),
        preferences.getInt("cadence", 60),
        preferences.getString("generation", "").orEmpty(),
    )

    fun save(settings: MonitoringSettings): MonitoringSettings = synchronized(Lock) {
        require(settings.minimumMinutes in listOf(60, 90, 120))
        require(settings.cadenceMinutes in listOf(15, 30, 60, 120, 360))
        val previous = settings()
        val next = settings.copy(generation = UUID.randomUUID().toString())
        val edit = preferences.edit()
            .putBoolean("enabled", next.enabled)
            .putInt("minimum", next.minimumMinutes)
            .putInt("cadence", next.cadenceMinutes)
            .putString("generation", next.generation)
        // Duration changes and re-enabling need a fresh silent baseline, not a flood.
        if (previous.enabled != next.enabled || previous.minimumMinutes != next.minimumMinutes) {
            edit.remove("history").remove("checkedAt").remove("error")
        }
        check(edit.commit()) { "Monitoring settings could not be saved" }
        next
    }

    companion object {
        // Serializes preference changes with the final worker commit/notification dispatch.
        val Lock = Any()
    }
}

internal const val AvailabilityChannel = "new-availability"
internal const val MonitoringWork = "bewegungswelt-availability"

internal fun notificationManager(context: Context): NotificationManager =
    context.getSystemService(NotificationManager::class.java)

internal fun ensureAvailabilityChannel(context: Context) {
    notificationManager(context).createNotificationChannel(
        NotificationChannel(AvailabilityChannel, "Neue freie Zeiten", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Neu buchbare Zeiten in der Bewegungswelt Waldau. Keine Reservierung."
        },
    )
}

internal fun notificationsAllowed(context: Context): Boolean {
    val manager = notificationManager(context)
    return manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(AvailabilityChannel)?.importance != NotificationManager.IMPORTANCE_NONE
}

internal fun scheduleMonitoring(
    context: Context,
    settings: MonitoringSettings,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
) {
    val work = WorkManager.getInstance(context)
    if (!settings.enabled) {
        work.cancelUniqueWork(MonitoringWork)
        notificationManager(context).cancelAll()
        return
    }
    val request = PeriodicWorkRequestBuilder<AvailabilityWorker>(settings.cadenceMinutes.toLong(), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()
    // A replaced schedule starts immediately, establishing its baseline without waiting an hour.
    work.enqueueUniquePeriodicWork(MonitoringWork, policy, request)
}
