package org.studyspace.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable data class SharedTimerState(val version: Long = 0, val timer: StudyTimer? = null, val lastSavedId: String? = null, val serverNow: Long = 0, val splitLogging: Boolean = false)
class SharedTimerChanged(message: String, val state: SharedTimerState) : IllegalStateException(message)

class SharedNativeStopwatch(context: Context, private val repo: StudyRepository) {
    private val cache = context.getSharedPreferences("shared_stopwatch_v1", 0)
    private val legacy = context.getSharedPreferences("study_stopwatch_v1", 0)
    private val requests = Mutex()
    private var auth: Account? = null
    private var checkedLegacy = false
    val state = MutableStateFlow<SharedTimerState?>(null)
    val connected = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val clockOffset = MutableStateFlow(0L)
    val error = MutableStateFlow("")
    fun connect(account: Account) {
        if (auth == account) return
        auth = account; checkedLegacy = false; connected.value = false; error.value = ""
        state.value = runCatching { cache.getString(account.scope(), null)?.let { codec.decodeFromString<SharedTimerState>(it) } }.getOrNull()
        clockOffset.value = cache.getLong(account.scope() + ".offset", 0)
    }
    private suspend fun send(account: Account, input: JsonObject): SharedTimerState {
        val before = System.currentTimeMillis()
        try {
            val result = repo.timerRequest(account, input)
            apply(account, result, result.serverNow - (before + System.currentTimeMillis()) / 2)
            return result
        } catch (changed: SharedTimerChanged) {
            apply(account, changed.state, changed.state.serverNow - (before + System.currentTimeMillis()) / 2)
            throw changed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (auth == account && repo.account.value == account) { connected.value = false; error.value = failure.message.orEmpty() }; throw failure }
    }
    private suspend fun apply(account: Account, result: SharedTimerState, offset: Long) {
        if (auth != account || repo.account.value != account || result.version < (state.value?.version ?: -1)) return
        val previous = state.value
        val previousSaved = previous?.lastSavedId
        if (previous?.version != result.version || previous?.splitLogging != result.splitLogging) state.value = result; clockOffset.value = offset; connected.value = true; error.value = ""
        if (previous?.version != result.version) cache.edit().putString(account.scope(), codec.encodeToString(result)).putLong(account.scope() + ".offset", offset).apply()
        if (result.lastSavedId != null && result.lastSavedId != previousSaved) repo.sync()
    }
    suspend fun poll() {
        if (requests.isLocked) return
        requests.withLock {
            val account = auth ?: return
            var result = send(account, buildJsonObject { put("action", "read") })
            if (!checkedLegacy) {
                val old = legacy.getString(account.scope(), null)
                val timer = runCatching { old?.let { codec.decodeFromString<StudyTimer>(it) } }.getOrNull()
                if (timer != null && repo.snapshot.value.records.none { it.id == timer.id } && result.version == 0L) result = send(account, buildJsonObject { put("action", "import"); put("version", 0); put("device", "Android"); put("timer", jsonOf(timer)) })
                if (old != null) legacy.edit().putString(account.scope() + ".previous-local", old).remove(account.scope()).apply()
                checkedLegacy = true
            }
        }
    }
    suspend fun command(action: String, expected: SharedTimerState = state.value ?: kotlin.error("Connect the stopwatch first."), entry: StudySession? = null, reviewOn: String? = null, entries: List<StudySession>? = null, totalMinutes: Int? = null): SharedTimerState {
        require(entries==null || state.value?.splitLogging==true) { "Update your sync server before saving a split stopwatch session." }
        busy.value = true
        try {
            return requests.withLock {
                val account = auth ?: kotlin.error("Sign in to control your shared timer.")
                send(account, buildJsonObject {
                    put("action",action);put("version",expected.version);put("device","Android")
                    expected.timer?.let { put("id",it.id) }
                    entry?.let { put("entry",jsonOf(it)) }
                    entries?.let { values -> put("entries",buildJsonArray { values.forEach { add(jsonOf(it)) } });put("totalMinutes",totalMinutes) }
                    if (reviewOn!=null) {
                        val reviews=(entries ?: listOfNotNull(entry)).filter { it.activity=="Revision" && it.topicId!=null }.distinctBy { it.topicId }
                        if(entries==null && reviews.isNotEmpty()) put("review",buildJsonObject { put("reviewOn",reviewOn) })
                        else put("reviews",buildJsonArray { reviews.forEach { add(buildJsonObject { put("topicId",it.topicId);put("reviewOn",reviewOn) }) } })
                    }
                })
            }
        } finally { busy.value = false }
    }
    suspend fun checkpoint() = requests.withLock { withContext(Dispatchers.IO) {
        val account=auth ?: return@withContext
        require(repo.account.value==account) { "The account changed. Try the update again." }
        val current=state.value ?: return@withContext
        check(cache.edit().putString(account.scope(),codec.encodeToString(current)).putLong(account.scope()+".offset",clockOffset.value).commit()) { "Could not save the shared stopwatch cache. The update has been postponed." }
    } }
}

@Composable fun SharedStudyStopwatch(vm: NativeViewModel, account: Account) {
    val controller = vm.sharedStopwatch
    val state by controller.state.collectAsStateWithLifecycle()
    val connected by controller.connected.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val offset by controller.clockOffset.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(account) {
        controller.connect(account)
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { try { controller.poll() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }; delay(2000) }
        }
    }
    LaunchedEffect(state?.timer) { now = System.currentTimeMillis(); if (state?.timer?.startedAt != null) while (isActive) { delay(1000); now = System.currentTimeMillis() } }
    fun act(action: String) {
        val expected = state ?: return
        scope.launch {
            try {
                val result = controller.command(action, expected)
                if (action == "finish" && repoAccountMatches(vm, account)) result.timer?.let { vm.open(Editor.LogForm(timer = it, timerScope = account.scope(), timerVersion = result.version)) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { vm.repo.messages.emit(failure.message ?: "Timer control failed") }
        }
    }
    val timer = state?.timer
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        if (timer == null) Action(if (busy) "Starting…" else "Start stopwatch", secondary = true, enabled = connected && !busy, icon = Icons.Outlined.PlayArrow) { vm.focusDial.value = true; act("start") }
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(timerClock(timer.elapsed(now + offset)), fontFamily = Sans, fontSize = 21.sp, color = Palette.sage)
                Text("${if (timer.startedAt != null) "One timer running" else if (timer.finished) "Ready to log" else "Paused"} · started on ${timer.origin ?: "another device"}", color = Palette.muted, fontSize = 10.sp, fontFamily = Sans)
            }
            if (busy) CircularProgressIndicator(Modifier.padding(14.dp).size(18.dp), color = Palette.sage, strokeWidth = 2.dp)
            else IconButton(enabled = connected, onClick = { act(if (timer.startedAt == null) "resume" else "pause") }) { Icon(if (timer.startedAt == null) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (timer.startedAt == null) "Resume shared timer" else "Pause shared timer", tint = if (connected) Palette.sage else Palette.muted) }
            Box {
                IconButton(enabled = connected && !busy, onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Shared timer actions", tint = Palette.muted) }
                DropdownMenu(menu, { menu = false }, containerColor = Palette.raised) {
                    DropdownMenuItem(text = { Body(if (timer.finished) "Log time" else "Finish & log", color = Palette.text) }, onClick = { menu = false; act("finish") })
                    DropdownMenuItem(text = { Body("Discard shared timer", color = Palette.danger) }, onClick = { menu = false; discard = true })
                }
            }
        }
        Action("Open focus dial", secondary = true) { vm.focusDial.value = true }
        if (!connected) Text(if (error.isBlank()) "Connecting shared stopwatch…" else "Reconnecting · $error", fontFamily = Sans, color = Palette.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, containerColor = Palette.raised,
        title = { Text("Discard shared timer?", color = Palette.text) }, text = { Body("This removes the unsaved timer from every device signed in to this account.") },
        confirmButton = { TextButton(enabled = connected && !busy, onClick = { discard = false; act("discard") }) { Text("Discard", color = Palette.danger) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep timer", color = Palette.sage) } })
}
private fun repoAccountMatches(vm: NativeViewModel, account: Account) = vm.repo.account.value == account
