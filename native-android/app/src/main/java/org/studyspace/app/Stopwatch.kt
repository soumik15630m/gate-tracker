package org.studyspace.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class NativeStopwatch(context: Context, workspace: StateFlow<String>, coroutineScope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("study_stopwatch_v1", Context.MODE_PRIVATE)
    private var scope = workspace.value
    private val _timer = MutableStateFlow<StudyTimer?>(null)
    val timer = _timer.asStateFlow()
    init { coroutineScope.launch { workspace.collect { key ->
        scope = key
        _timer.value = runCatching { preferences.getString(key, null)?.let { codec.decodeFromString<StudyTimer>(it) } }
            .getOrNull()?.takeIf { it.accumulatedMs >= 0 && (it.startedAt == null || it.startedAt >= 0) }
    } } }
    private fun update(value: StudyTimer?) {
        _timer.value = value
        preferences.edit().apply { if (value == null) remove(scope) else putString(scope, codec.encodeToString(value)) }.apply()
    }
    fun start() { if (_timer.value == null) update(StudyTimer(uid("session"), startedAt = System.currentTimeMillis())) }
    fun toggle() { _timer.value?.let { update(if (it.startedAt == null) it.resume() else it.pause()) } }
    fun finish(): StudyTimer? = _timer.value?.pause()?.also { update(it) }
    fun consume(id: String, workspace: String) { if (workspace == scope && _timer.value?.id == id) update(null) }
    fun discard() = update(null)
}

// Only this small composable ticks; the surrounding syllabus list stays idle.
@Composable fun StudyStopwatch(vm: NativeViewModel) {
    val timer by vm.stopwatch.timer.collectAsStateWithLifecycle()
    val scope by vm.repo.workspace.collectAsStateWithLifecycle()
    val saved by vm.repo.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(timer?.id, saved.records, scope) { timer?.let { current -> if (saved.sessions.any { it.id == current.id }) vm.stopwatch.consume(current.id, scope) } }
    var discard by remember(scope) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) { now = System.currentTimeMillis(); if (timer?.startedAt != null) while (true) { delay(1000); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (timer == null) {
            Action("Start stopwatch", secondary = true, icon = Icons.Outlined.PlayArrow) { vm.stopwatch.start() }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(timerClock(timer!!.elapsed(now)), color = Palette.sage, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 24.sp, modifier = Modifier.weight(1f))
                Label(if (timer!!.startedAt == null) "PAUSED" else "STUDYING", Palette.muted)
                IconAction("Discard stopwatch", Icons.Outlined.DeleteOutline) { discard = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(if (timer!!.startedAt == null) "Resume" else "Pause", Modifier.weight(1f), secondary = true, icon = if (timer!!.startedAt == null) Icons.Outlined.PlayArrow else Icons.Outlined.Pause) { vm.stopwatch.toggle() }
                Action("Finish & log", Modifier.weight(1f)) {
                    vm.stopwatch.finish()?.let { vm.open(Editor.LogForm(timer = it, timerScope = scope)) }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, containerColor = Palette.raised,
        title = { Text("Discard unsaved time?", color = Palette.text) },
        text = { Body("This removes ${timerClock(timer?.elapsed(now) ?: 0)} from the stopwatch. Saved study entries stay as they are.") },
        confirmButton = { TextButton(onClick = { vm.stopwatch.discard(); discard = false }) { Text("Discard", color = Palette.danger) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep studying", color = Palette.sage) } })
}
