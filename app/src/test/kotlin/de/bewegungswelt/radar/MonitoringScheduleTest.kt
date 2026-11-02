package de.bewegungswelt.radar

import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class MonitoringScheduleTest {
    @Test fun `monitoring is unique network constrained configurable and cancellable`() {
        val context: Context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val work = WorkManager.getInstance(context)
        val defaults = MonitoringSettings(enabled = true)
        scheduleMonitoring(context, defaults)
        val initial = work.getWorkInfosForUniqueWork(MonitoringWork).get().single()
        assertEquals(NetworkType.CONNECTED, initial.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(1), initial.periodicityInfo!!.repeatIntervalMillis)
        scheduleMonitoring(context, defaults, ExistingPeriodicWorkPolicy.KEEP)
        assertEquals(initial.id, work.getWorkInfosForUniqueWork(MonitoringWork).get().single().id)
        scheduleMonitoring(context, defaults.copy(cadenceMinutes = 120))
        val active = work.getWorkInfosForUniqueWork(MonitoringWork).get().single { !it.state.isFinished }
        assertEquals(TimeUnit.HOURS.toMillis(2), active.periodicityInfo!!.repeatIntervalMillis)
        assertNotEquals(initial.id, active.id)
        scheduleMonitoring(context, defaults.copy(enabled = false))
        assertTrue(work.getWorkInfosForUniqueWork(MonitoringWork).get().all { it.state == WorkInfo.State.CANCELLED })
    }
}
