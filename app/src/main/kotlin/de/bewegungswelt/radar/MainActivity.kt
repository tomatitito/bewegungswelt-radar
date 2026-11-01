package de.bewegungswelt.radar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.bewegungswelt.radar.locaboo.LocabooAvailabilitySource
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val calendar: CalendarViewModel = viewModel(factory = viewModelFactory {
                initializer { CalendarViewModel(LocabooAvailabilitySource()) }
            })
            val state by calendar.state.collectAsStateWithLifecycle()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(calendar, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    calendar.refresh()
                    while (true) {
                        calendar.updateTime()
                        delay(30_000)
                    }
                }
            }
            CalendarTheme {
                CalendarScreen(state, calendar::showRange, calendar::refresh)
            }
        }
    }
}
