package de.bewegungswelt.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bewegungswelt.radar.availability.AvailabilityLoadException
import de.bewegungswelt.radar.availability.AvailabilitySource
import de.bewegungswelt.radar.availability.calculateAvailability
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SourceDiagnostic
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CalendarUiState(
    val range: CalendarRange? = null,
    val snapshot: CalendarSnapshot? = null,
    val slots: List<Slot> = emptyList(),
    val loading: Boolean = true,
    val diagnostic: SourceDiagnostic? = null,
) {
    val confirmed: Boolean get() = !loading && diagnostic == null && snapshot != null
}

/** Owns requests and interpreted data; navigation stays saveable in the calendar. */
internal class CalendarViewModel(
    private val source: AvailabilitySource,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(CalendarUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L

    fun showRange(range: CalendarRange) {
        if (state.value.range != range) load(range)
    }

    fun refresh() {
        val current = state.value
        if (!current.loading) current.range?.let(::load)
    }

    private fun load(range: CalendarRange) {
        val currentGeneration = ++generation
        request?.cancel()
        // Keep only the last fetch timestamp/rules for this period, never stale green slots.
        val previous = state.value.snapshot?.takeIf { state.value.range == range }
        mutableState.value = CalendarUiState(range = range, snapshot = previous)
        request = viewModelScope.launch {
            try {
                val snapshot = source.loadCalendar(range)
                val slots = slots(range, snapshot)
                if (generation == currentGeneration) {
                    mutableState.value = CalendarUiState(range, snapshot, slots, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == currentGeneration) {
                    mutableState.value = CalendarUiState(
                        range = range,
                        snapshot = previous,
                        loading = false,
                        diagnostic = (failure as? AvailabilityLoadException)?.diagnostic
                            ?: SourceDiagnostic("Verfügbarkeit konnte nicht geladen werden."),
                    )
                }
            }
        }
    }

    /** Re-evaluate past starts on the foreground clock without extra upstream requests. */
    fun updateTime() {
        val previous = state.value
        if (!previous.confirmed) return
        viewModelScope.launch {
            val slots = slots(checkNotNull(previous.range), checkNotNull(previous.snapshot))
            if (state.value === previous) mutableState.value = previous.copy(slots = slots)
        }
    }

    private suspend fun slots(range: CalendarRange, snapshot: CalendarSnapshot): List<Slot> =
        withContext(Dispatchers.Default) { calculateAvailability(range, snapshot, clock.instant()) }
}
