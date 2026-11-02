package de.bewegungswelt.radar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.ExistingPeriodicWorkPolicy
import de.bewegungswelt.radar.locaboo.LocabooAvailabilitySource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var openDate by mutableStateOf<LocalDate?>(null)
    private var openRequest by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotification(intent)
    }

    private fun acceptNotification(intent: Intent) {
        openDate = intent.getStringExtra("availabilityDate")?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()
        }
        openRequest++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) acceptNotification(intent)
        ensureAvailabilityChannel(this)
        scheduleMonitoring(this, MonitoringStore(this).settings(), ExistingPeriodicWorkPolicy.KEEP)
        setContent {
            val store = remember { MonitoringStore(this) }
            var monitoring by remember { mutableStateOf(store.settings()) }
            var allowed by remember { mutableStateOf(notificationsAllowed(this)) }
            var showPreferences by rememberSaveable { mutableStateOf(false) }
            var pendingMinimum by rememberSaveable { mutableIntStateOf(0) }
            var pendingCadence by rememberSaveable { mutableIntStateOf(60) }
            fun save(settings: MonitoringSettings) {
                monitoring = store.save(settings)
                scheduleMonitoring(this, monitoring)
                showPreferences = false
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                allowed = notificationsAllowed(this)
                if (granted && allowed && pendingMinimum > 0) {
                    save(monitoring.copy(enabled = true, minimumMinutes = pendingMinimum, cadenceMinutes = pendingCadence))
                }
                pendingMinimum = 0
            }
            val calendar: CalendarViewModel = viewModel(factory = viewModelFactory {
                initializer { CalendarViewModel(LocabooAvailabilitySource()) }
            })
            val state by calendar.state.collectAsStateWithLifecycle()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(calendar, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    allowed = notificationsAllowed(this@MainActivity)
                    calendar.refresh()
                    while (true) {
                        calendar.updateTime()
                        delay(30_000)
                    }
                }
            }
            LaunchedEffect(openRequest) { calendar.refresh() }
            CalendarTheme {
                CalendarScreen(state, calendar::showRange, calendar::refresh,
                    openDate = openDate, openRequest = openRequest,
                    onOpenNotifications = {
                        monitoring = store.settings()
                        allowed = notificationsAllowed(this)
                        showPreferences = true
                    })
                if (showPreferences) MonitoringPreferences(
                    monitoring, allowed,
                    store.preferences.getString("checkedAt", null)?.let {
                        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Berlin"))
                            .format(Instant.parse(it))
                    },
                    store.preferences.getBoolean("error", false),
                    onSave = { desired ->
                        if (!desired.enabled || allowed) save(desired)
                        else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {
                            pendingMinimum = desired.minimumMinutes
                            pendingCadence = desired.cadenceMinutes
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onSystemSettings = {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    },
                    onDismiss = { showPreferences = false },
                )
            }
        }
    }
}
