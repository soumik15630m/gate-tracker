@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.studyspace.app

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.min

object Palette {
    val background = Color.Black
    val panel = Color(0xFF0D100F)
    val raised = Color(0xFF141917)
    val border = Color(0xFF252D29)
    val text = Color(0xFFECEFE9)
    val muted = Color(0xFF929E95)
    val sage = Color(0xFFA5B9A3)
    val teal = Color(0xFF66B9AB)
    val gold = Color(0xFFC6AD72)
    val danger = Color(0xFFDC9690)
}
val Sans = FontFamily(Font(R.font.dm_sans_regular), Font(R.font.dm_sans_medium, FontWeight.Medium))
val Serif = FontFamily(Font(R.font.instrument_serif))
@Composable fun StudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.sage, onPrimary = Color.Black, secondary = Palette.teal, background = Color.Black, surface = Palette.panel, onSurface = Palette.text, onBackground = Palette.text, outline = Palette.border, error = Palette.danger), typography = Typography(
        bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 23.sp), bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp), labelLarge = TextStyle(fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    ), content = content)
}
@Composable fun Label(text: String, color: Color = Palette.muted, modifier: Modifier = Modifier) {
    Text(text, color = color, fontFamily = Sans, fontSize = 10.sp, letterSpacing = 1.6.sp, modifier = modifier)
}
@Composable fun Body(text: String, modifier: Modifier = Modifier, color: Color = Palette.muted, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = color, fontFamily = Sans, fontSize = 13.sp, lineHeight = 20.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
@Composable fun PageHeading(eyebrow: String, title: String, subtitle: String = "", action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(eyebrow.uppercase(), Palette.sage)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = Palette.text, fontFamily = Serif, fontSize = 35.sp, lineHeight = 39.sp)
            action?.invoke()
        }
        if (subtitle.isNotEmpty()) Body(subtitle)
    }
}
@Composable fun Card(modifier: Modifier = Modifier, title: String? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.panel).border(1.dp, Palette.border, RoundedCornerShape(18.dp)).padding(19.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (title != null) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, color = Palette.text, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp); if (subtitle != null) Body(subtitle) }
        content()
    }
}
@Composable fun Action(label: String, modifier: Modifier = Modifier, secondary: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val color = if (secondary) Palette.raised else Palette.sage
    Row(modifier.defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) color else Palette.border).border(if (secondary) 1.dp else 0.dp, Palette.border, RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (secondary) Palette.text else Color.Black, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)) }
        Text(label, fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (secondary) Palette.text else Color.Black)
    }
}
@Composable fun IconAction(label: String, icon: ImageVector, color: Color = Palette.muted, onClick: () -> Unit) {
    IconButton(onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) { Icon(icon, null, tint = color, modifier = Modifier.size(21.dp)) }
}
@Composable fun Chip(label: String, selected: Boolean = false, color: Color = Palette.sage, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(9.dp)
    val interactive = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick).defaultMinSize(minHeight = 44.dp) else Modifier
    Box(Modifier.clip(shape).background(if (selected) color.copy(alpha = .15f) else Palette.raised).border(1.dp, if (selected) color.copy(alpha = .4f) else Palette.border, shape).then(interactive).padding(horizontal = 11.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, fontFamily = Sans, fontSize = 11.sp, color = if (selected) color else Palette.muted)
    }
}
@Composable fun Meter(percent: Int, modifier: Modifier = Modifier, color: Color = Palette.teal) {
    Box(modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.border).semantics { progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f) }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((percent / 100f).coerceIn(0f, 1f)).background(color))
    }
}
@Composable fun ProgressCheck(progress: Progress, label: String, onClick: () -> Unit) {
    val complete = progress.total > 0 && progress.done == progress.total
    Box(Modifier.size(48.dp).semantics { contentDescription = "$label, ${progress.done} of ${progress.total} complete"; role = Role.Checkbox; stateDescription = if (complete) "Complete" else if (progress.done > 0) "Partly complete" else "Incomplete" }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(21.dp).clip(RoundedCornerShape(6.dp)).background(if (complete) Palette.sage else Palette.raised).border(1.dp, if (complete) Palette.sage else Palette.muted, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (complete) Icon(Icons.Outlined.Check, null, tint = Color.Black, modifier = Modifier.size(15.dp))
            else if (progress.done > 0) Box(Modifier.width(10.dp).height(2.dp).background(Palette.sage))
        }
    }
}
@Composable fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, multiline: Boolean = false, numeric: Boolean = false, password: Boolean = false, hint: String = "") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Palette.muted, fontSize = 12.sp, fontFamily = Sans)
        BasicTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().testTag(label).semantics { contentDescription = label }.clip(RoundedCornerShape(12.dp)).background(Palette.raised).border(1.dp, Palette.border, RoundedCornerShape(12.dp)).defaultMinSize(minHeight = if (multiline) 100.dp else 50.dp).padding(14.dp),
            textStyle = TextStyle(color = Palette.text, fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp), cursorBrush = SolidColor(Palette.sage), singleLine = !multiline, maxLines = if (multiline) 7 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else if (numeric) KeyboardType.Decimal else KeyboardType.Text), visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner -> Box { if (value.isEmpty() && hint.isNotBlank()) Body(hint, color = Palette.muted.copy(alpha = .65f)); inner() } })
    }
}
@Composable fun DateField(label: String, value: String, onChange: (String) -> Unit, allowEmpty: Boolean = false) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Body(label)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.raised).clickable(role = Role.Button) {
            val day = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
            DatePickerDialog(context, android.R.style.Theme_Material_Dialog_Alert, { _, year, month, date -> onChange(LocalDate.of(year, month + 1, date).toString()) }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarToday, null, tint = Palette.sage, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(12.dp))
            Text(if (value.isBlank()) "Choose a date" else displayDate(value), color = Palette.text, fontFamily = Sans, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (allowEmpty && value.isNotBlank()) IconAction("Clear $label", Icons.Outlined.Close) { onChange("") } else Spacer(Modifier.height(50.dp).width(14.dp))
        }
    }
}
@Composable fun Choice(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Body(label)
        Box {
            Row(Modifier.fillMaxWidth().testTag("$label selector").clip(RoundedCornerShape(12.dp)).background(Palette.raised).clickable { expanded = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f), color = Palette.text, fontFamily = Sans, fontSize = 14.sp)
                Icon(Icons.Outlined.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = Palette.raised) {
                options.forEach { (key, name) -> DropdownMenuItem(text = { Text(name, fontFamily = Sans, color = Palette.text) }, onClick = { onChange(key); expanded = false }) }
            }
        }
    }
}
@Composable fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Palette.sage, checkmarkColor = Color.Black, uncheckedColor = Palette.muted))
        Body(label, color = Palette.text)
    }
}
@Composable fun EmptyCard(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) { Card(title = title) { Body(subtitle); action?.invoke() } }
@Composable fun Stat(label: String, value: String, detail: String, modifier: Modifier = Modifier, color: Color = Palette.text) {
    Card(modifier) { Label(label); Text(value, fontFamily = Serif, fontSize = 34.sp, lineHeight = 39.sp, color = color); Body(detail, maxLines = 2) }
}
@Composable fun StatPair(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) { left(Modifier.weight(1f)); right(Modifier.weight(1f)) } }
fun LazyListScope.spacer(key: String) { item(key = key, contentType = "space") { Spacer(Modifier.height(4.dp)) } }

@Composable fun TimeBars(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier, dates: List<String> = emptyList(), sessions: List<StudySession> = emptyList(), bucketDays: Int = 1) {
    var selected by remember { mutableStateOf<String?>(null) }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(128.dp).pointerInput(values, dates) { detectTapGestures { offset -> if (dates.isNotEmpty()) selected = dates.getOrNull((offset.x / (size.width / values.size.coerceAtLeast(1).toFloat())).toInt().coerceIn(0, dates.lastIndex)) } }.semantics { contentDescription = "Study time chart: ${values.joinToString()} minutes" }) {
            val gap = size.width / values.size.coerceAtLeast(1)
            repeat(3) { line -> val y = size.height * line / 3; drawLine(Palette.border, Offset(0f, y), Offset(size.width, y), 1f) }
            values.forEachIndexed { index, value ->
                val height = (value / max.toFloat()) * (size.height - 8.dp.toPx())
                drawRoundRect(if (index == values.lastIndex) Palette.sage else Palette.teal.copy(alpha = .7f), Offset(index * gap + gap * .2f, size.height - height), Size(gap * .6f, height.coerceAtLeast(2f)), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { labels.forEach { Text(it, color = Palette.muted, fontFamily = Sans, fontSize = 10.sp) } }
    }
    selected?.let { day -> StudyDayDialog(day, sessions, bucketDays) { selected = null } }
}
@Composable fun ActivityGrid(days: Map<String, Int>, today: String, modifier: Modifier = Modifier, sessions: List<StudySession> = emptyList()) {
    var selected by remember { mutableStateOf<String?>(null) }
    val values = remember(days, today) { (90L downTo 0L).map { days[addDays(today, -it)] ?: 0 } }
    Canvas(modifier.fillMaxWidth().height(110.dp).pointerInput(days, today) {
        detectTapGestures { offset ->
            val gap = 4.dp.toPx(); val cell = min((size.width - gap * 12) / 13, (size.height - gap * 6) / 7)
            val column = (offset.x / (cell + gap)).toInt(); val row = (offset.y / (cell + gap)).toInt()
            if (column in 0..12 && row in 0..6 && offset.x % (cell + gap) <= cell && offset.y % (cell + gap) <= cell) selected = addDays(today, (column * 7 + row - 90).toLong())
        }
    }.semantics { contentDescription = "Activity over the past 91 days. ${values.count { it > 0 }} study days." }) {
        val columns = 13; val gap = 4.dp.toPx(); val cell = min((size.width - gap * (columns - 1)) / columns, (size.height - gap * 6) / 7)
        values.forEachIndexed { index, minutes ->
            val color = when { minutes == 0 -> Palette.border; minutes < 60 -> Palette.teal.copy(alpha = .3f); minutes < 120 -> Palette.teal.copy(alpha = .55f); minutes < 180 -> Palette.teal.copy(alpha = .8f); else -> Palette.teal }
            drawRoundRect(color, Offset(index / 7 * (cell + gap), index % 7 * (cell + gap)), Size(cell, cell), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
    selected?.let { day -> StudyDayDialog(day, sessions) { selected = null } }
}
@Composable fun TrendLine(values: List<Double>, modifier: Modifier = Modifier, color: Color = Palette.teal, maximum: Double = 100.0) {
    Canvas(modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Trend: ${values.joinToString { "%.1f".format(it) }}" }) {
        repeat(3) { i -> val y = size.height * i / 3; drawLine(Palette.border, Offset(0f, y), Offset(size.width, y), 1f) }
        if (values.isNotEmpty()) {
            val coordinates = values.mapIndexed { i, v -> Offset(if (values.size == 1) size.width / 2 else size.width * i / (values.size - 1), size.height - 8.dp.toPx() - ((v / maximum.coerceAtLeast(1.0)).coerceIn(0.0, 1.0) * (size.height - 16.dp.toPx())).toFloat()) }
            val path = Path().apply { coordinates.forEachIndexed { i, point -> if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) } }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)); coordinates.forEach { drawCircle(color, 3.dp.toPx(), it) }
        }
    }
}

@Composable private fun StudyDayDialog(day: String, sessions: List<StudySession>, bucketDays: Int = 1, onClose: () -> Unit) {
    val end = addDays(day, (bucketDays - 1).toLong())
    val entries = remember(day, end, sessions) { sessions.filter { it.date in day..end } }
    AlertDialog(onDismissRequest = onClose, containerColor = Palette.panel,
        title = { Body(if (bucketDays == 1) displayDate(day) else "${displayDate(day)} to ${displayDate(end)}", color = Palette.text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Body("${duration(entries.sumOf { it.minutes })} / ${entries.size} study entries", color = Palette.teal)
            entries.groupBy { it.activity }.forEach { (activity, items) -> Body("$activity / ${duration(items.sumOf { it.minutes })}") }
            if (entries.isEmpty()) Body("No study time recorded.")
            LazyColumn(Modifier.heightIn(max = 230.dp)) { items(entries, key = { it.id }) { entry -> Body("${entry.topicPath.ifBlank { "Whole exam" }} / ${duration(entry.minutes)}", Modifier.padding(vertical = 8.dp)) } }
        } }, confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Palette.sage) } })
}
