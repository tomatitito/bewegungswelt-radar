package de.bewegungswelt.radar

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SlotStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val Background = Color(0xFF1D1A18)
private val Panel = Color(0xFF252422)
private val Selection = Color(0xFFB5B3AA)
private val Bookable = Color(0xFF28643C)
private val Outline = Color(0xFF55534F)
private val SecondaryText = Color(0xFFBDBBB6)
private val CalendarZone = ZoneId.of("Europe/Berlin")
private val FullDate = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
private val MonthLabel = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
private val TimeLabel = DateTimeFormatter.ofPattern("HH:mm").withZone(CalendarZone)

@Composable
internal fun CalendarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Selection,
            onPrimary = Background,
            background = Background,
            onBackground = Color(0xFFF0EFEB),
            surface = Panel,
            onSurface = Color(0xFFF0EFEB),
            onSurfaceVariant = SecondaryText,
            outline = Outline,
        ),
        content = content,
    )
}

/** Receives already interpreted slots through a pure lookup; never fetches provider data. */
@Composable
internal fun CalendarScreen(
    slotsForDay: (LocalDate) -> List<Slot>,
    today: LocalDate = LocalDate.now(CalendarZone),
) {
    var selectedEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var monthEpoch by rememberSaveable { mutableLongStateOf(today.withDayOfMonth(1).toEpochDay()) }
    var monthMode by rememberSaveable { mutableStateOf(false) }
    var detailStart by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    val month = YearMonth.from(LocalDate.ofEpochDay(monthEpoch))
    val weekStart = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val slots = slotsForDay(selected)
    val scroll = rememberScrollState()
    val largeText = LocalDensity.current.fontScale > 1.3f
    val timeFraction = if (largeText) 0.35f else 0.25f
    LaunchedEffect(monthMode) { scroll.scrollTo(0) }

    Surface(color = Background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.venue_name), color = SecondaryText, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(R.string.demo_notice),
                    color = Color(0xFFFFA5A9),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3A2325), RoundedCornerShape(10.dp)).padding(12.dp),
                )
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp))
                        .background(Panel).padding(3.dp).selectableGroup(),
                ) {
                    listOf(false to R.string.week, true to R.string.month).forEach { (isMonth, label) ->
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(if (monthMode == isMonth) Selection else Color.Transparent)
                                .selectable(selected = monthMode == isMonth, role = Role.Tab, onClick = {
                                    if (isMonth && !monthMode) monthEpoch = selected.withDayOfMonth(1).toEpochDay()
                                    monthMode = isMonth
                                }).heightIn(min = 48.dp).padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(label), color = if (monthMode == isMonth) Background else SecondaryText, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NavigationArrow(false, monthMode) {
                        if (monthMode) monthEpoch = month.minusMonths(1).atDay(1).toEpochDay()
                        else selectedEpoch = selected.minusWeeks(1).toEpochDay()
                    }
                    Text(
                        if (monthMode) month.format(MonthLabel)
                        else "${weekStart.format(DateTimeFormatter.ofPattern("d. MMM", Locale.GERMAN))} – ${weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMAN))}",
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = {
                        selectedEpoch = today.toEpochDay()
                        monthEpoch = today.withDayOfMonth(1).toEpochDay()
                    }) { Text(stringResource(R.string.today)) }
                    NavigationArrow(true, monthMode) {
                        if (monthMode) monthEpoch = month.plusMonths(1).atDay(1).toEpochDay()
                        else selectedEpoch = selected.plusWeeks(1).toEpochDay()
                    }
                }
                Column(Modifier.animateContentSize()) {
                    if (monthMode) {
                        Row(Modifier.fillMaxWidth()) {
                            DayOfWeek.entries.forEach { day ->
                                Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(day.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix("."), color = SecondaryText)
                                }
                            }
                        }
                        val offset = month.atDay(1).dayOfWeek.value - 1
                        val rows = (offset + month.lengthOfMonth() + 6) / 7
                        repeat(rows) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                repeat(7) { column ->
                                    val number = row * 7 + column - offset + 1
                                    if (number in 1..month.lengthOfMonth()) {
                                        val date = month.atDay(number)
                                        DayCell(date, date == selected, slotsForDay(date).any { it.status == SlotStatus.BOOKABLE }, false, Modifier.weight(1f)) {
                                            selectedEpoch = date.toEpochDay()
                                            monthMode = false
                                        }
                                    } else Spacer(Modifier.weight(1f).height(48.dp))
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { index ->
                                val date = weekStart.plusDays(index.toLong())
                                DayCell(date, date == selected, slotsForDay(date).any { it.status == SlotStatus.BOOKABLE }, true, Modifier.weight(1f)) {
                                    selectedEpoch = date.toEpochDay()
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected.format(FullDate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.slot_duration), color = SecondaryText, style = MaterialTheme.typography.bodyMedium)
                }
                Column(Modifier.fillMaxWidth().border(1.dp, Outline, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
                    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.time), Modifier.weight(timeFraction), color = SecondaryText, style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.status), Modifier.weight(1f - timeFraction), color = SecondaryText, style = MaterialTheme.typography.labelMedium)
                    }
                    if (slots.isEmpty()) Text(stringResource(R.string.no_slots), Modifier.padding(16.dp))
                    slots.forEach { slot ->
                        HorizontalDivider(color = Outline)
                        val label = stringResource(statusLabel(slot.status))
                        val description = stringResource(R.string.slot_description, TimeLabel.format(slot.window.start), TimeLabel.format(slot.window.endExclusive), label)
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.slot_details)) {
                                detailStart = slot.window.start.toString()
                            }.semantics(mergeDescendants = true) { contentDescription = description }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(TimeLabel.format(slot.window.start), Modifier.weight(timeFraction), style = MaterialTheme.typography.bodyLarge)
                            Row(
                                Modifier.weight(1f - timeFraction).background(statusColor(slot.status), RoundedCornerShape(8.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!largeText) {
                                    SlotIcon(slot.status)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(label, color = statusTextColor(slot.status), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.read_only_footer), color = SecondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    slots.find { it.window.start.toString() == detailStart }?.let { slot ->
        AlertDialog(
            onDismissRequest = { detailStart = null },
            containerColor = Panel,
            title = { Text(stringResource(R.string.slot_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selected.format(FullDate))
                    Text("${TimeLabel.format(slot.window.start)} – ${TimeLabel.format(slot.window.endExclusive)}")
                    Text(stringResource(statusLabel(slot.status)))
                    Text(stringResource(R.string.read_only_details), color = SecondaryText)
                }
            },
            confirmButton = { TextButton(onClick = { detailStart = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun NavigationArrow(next: Boolean, month: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (month) {
        if (next) R.string.next_month else R.string.previous_month
    } else {
        if (next) R.string.next_week else R.string.previous_week
    })
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Outline, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (next) "›" else "‹", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, bookable: Boolean, week: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val description = date.format(FullDate) + ", " + stringResource(if (bookable) R.string.day_bookable else R.string.day_no_bookable)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.padding(2.dp).clip(shape)
            .background(if (selected) Selection else if (bookable) Bookable else Panel)
            .border(if (selected) 2.dp else 1.dp, if (bookable) Bookable else Outline, shape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = if (week) 64.dp else 44.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val color = if (selected) Background else Color.White
        if (week) Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix("."), color = color, style = MaterialTheme.typography.labelMedium)
        Text(date.dayOfMonth.toString(), color = color, fontWeight = if (selected || bookable) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun statusLabel(status: SlotStatus) = when (status) {
    SlotStatus.BOOKABLE -> R.string.bookable
    SlotStatus.BOOKED -> R.string.booked
    SlotStatus.CLOSED -> R.string.closed
    SlotStatus.UNKNOWN -> R.string.unknown
}

private fun statusColor(status: SlotStatus) = when (status) {
    SlotStatus.BOOKABLE -> Bookable
    SlotStatus.BOOKED -> Color(0xFF3C4858)
    SlotStatus.CLOSED -> Color(0xFF555456)
    SlotStatus.UNKNOWN -> Color(0xFFAE8230)
}

private fun statusTextColor(status: SlotStatus) = if (status == SlotStatus.UNKNOWN) Background else Color.White

@Composable
private fun SlotIcon(status: SlotStatus) {
    val color = statusTextColor(status)
    if (status == SlotStatus.UNKNOWN) {
        Box(Modifier.size(20.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text("?", color = color, fontWeight = FontWeight.Bold)
        }
    } else Canvas(Modifier.size(20.dp).clearAndSetSemantics {}) {
        val u = size.minDimension / 20f
        when (status) {
            SlotStatus.BOOKABLE -> {
                drawCircle(color, radius = 9f * u, style = Stroke(1.5f * u))
                drawLine(color, Offset(5f * u, 10f * u), Offset(9f * u, 14f * u), 2f * u, StrokeCap.Round)
                drawLine(color, Offset(9f * u, 14f * u), Offset(15f * u, 6f * u), 2f * u, StrokeCap.Round)
            }
            SlotStatus.BOOKED -> {
                drawCircle(color, radius = 4f * u, center = Offset(10f * u, 5f * u))
                drawRoundRect(color, Offset(3f * u, 11f * u), Size(14f * u, 8f * u), CornerRadius(4f * u))
            }
            SlotStatus.CLOSED -> {
                drawRoundRect(color, Offset(6f * u, 1f * u), Size(8f * u, 11f * u), CornerRadius(4f * u), style = Stroke(2f * u))
                drawRoundRect(color, Offset(3f * u, 8f * u), Size(14f * u, 11f * u), CornerRadius(2f * u))
            }
            SlotStatus.UNKNOWN -> Unit
        }
    }
}

@Preview(showBackground = true, locale = "de", widthDp = 360)
@Composable
private fun CalendarPreview() {
    CalendarTheme { CalendarScreen(::demoSlots, today = LocalDate.of(2026, 11, 2)) }
}
