@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.studyspace.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        setContent { StudyTheme { StudyApp() } }
    }
}
enum class Screen(val title: String) { TODAY("Today"), SYLLABUS("Syllabus"), LOG("Study log"), INSIGHTS("Insights"), MORE("More"), REVISION("Revision"), MOCKS("Mock tests"), PLAN("Plan"), SETTINGS("Settings") }
sealed interface Editor {
    data class ExamForm(val value: Exam? = null) : Editor
    data class TopicForm(val value: Topic? = null, val parent: String? = null) : Editor
    data class LogForm(val value: StudySession? = null, val topic: String? = null) : Editor
    data class MockForm(val value: MockTest? = null) : Editor
    data class TaskForm(val value: StudyTask? = null, val type: String = "milestone") : Editor
    data class ReflectionForm(val value: Reflection? = null) : Editor
    data class ImportOutline(val parent: String? = null) : Editor
    data class TopicDetail(val id: String) : Editor
    data class MoveTopic(val id: String) : Editor
    data class Review(val id: String) : Editor
    data object QuickRevision : Editor
    data class Completion(val id: String, val recordOnly: Boolean = false) : Editor
    data class Delete(val kind: String, val id: String) : Editor
    data object Fresh : Editor
    data object SignIn : Editor
    data class RestoreBackup(val source: String, val count: Int) : Editor
}
class Workspace(val state: StudySnapshot = StudySnapshot(), selection: String = "all") {
    val selected = selection.takeIf { it == "all" || state.exams.any { exam -> exam.id == it } } ?: "all"
    val exam = state.exams.firstOrNull { it.id == selected }
    val activeExams = state.exams.filterNot { it.archived }
    private val selectedIds = if (selected == "all") activeExams.map { it.id }.toHashSet() else setOf(selected)
    val topics = state.topics.filter { it.examId in selectedIds }
    val sessions = state.sessions.filter { it.examId in selectedIds }
    val mocks = state.mocks.filter { it.examId in selectedIds }.sortedBy { it.date }
    val tasks = state.tasks.filter { it.examId in selectedIds }
    val reflections = state.reflections.filter { it.examId in selectedIds }
    val index = TopicIndex(topics, sessions)
    val day = state.today
    val days = studyDays(sessions)
    val weekMinutes = sessions.filter { it.date >= weekStart(day) && it.date <= day }.sumOf { it.minutes }
    val target = exam?.weeklyTarget ?: activeExams.sumOf { it.weeklyTarget }
    val nearest = exam ?: activeExams.filter { it.date >= day }.minByOrNull { it.date }
    val examId = exam?.id ?: activeExams.firstOrNull()?.id
}
class NativeViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as StudyspaceApplication).repository
    private val device = application.getSharedPreferences("native_device", 0)
    val selected = MutableStateFlow(device.getString("selected-exam", "all") ?: "all")
    val screen = MutableStateFlow(Screen.TODAY)
    val editor = MutableStateFlow<Editor?>(null)
    val busy = MutableStateFlow(false)
    val workingError = MutableStateFlow("")
    val updater = NativeUpdater(application)
    var mockDraft: MockDraft? = null
    val workspace = combine(repo.snapshot, selected) { state, selection -> state to selection }
        .mapLatest { (state, selection) -> withContext(Dispatchers.Default) { Workspace(state, selection) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Workspace())
    fun select(id: String) { selected.value = id; device.edit().putString("selected-exam", id).apply() }
    fun open(editor: Editor) { workingError.value = ""; if (editor is Editor.MockForm) mockDraft = MockDraft(editor.value); this.editor.value = editor }
    fun dismiss() { if (!busy.value) editor.value = null; workingError.value = "" }
    fun perform(close: Boolean = true, action: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; workingError.value = ""
            try { action(); if (close) editor.value = null }
            catch (e: Exception) { workingError.value = e.message ?: "This change could not be saved."; repo.error.value = workingError.value }
            finally { busy.value = false }
        }
    }
    fun save(changes: List<Mutation>, message: String = "Saved", close: Boolean = true) = perform(close) { repo.commit(changes, message) }
    fun toggleTopic(id: String) {
        if (workspace.value.index.progress[id]?.closedOn != null) perform(false) { repo.toggleTopic(id) }
        else open(Editor.Completion(id))
    }
    fun quickLog() = open(Editor.LogForm())
}

@Composable fun StudyApp(vm: NativeViewModel = viewModel()) {
    val ready by vm.repo.ready.collectAsStateWithLifecycle()
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    val route by vm.screen.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val failure by vm.repo.error.collectAsStateWithLifecycle()
    val account by vm.repo.account.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(vm) { vm.repo.messages.collect { snack.showSnackbar(it) } }
    LaunchedEffect(vm, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.repo.sync()
            while (true) { delay(15000); vm.repo.sync() }
        }
    }
    LaunchedEffect(vm) { vm.updater.checkIfDue() }
    BackHandler(editor != null || route != Screen.TODAY) { if (editor != null) vm.dismiss() else vm.screen.value = Screen.TODAY }
    Scaffold(containerColor = Color.Black, contentColor = Palette.text, contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snack) }, bottomBar = { BottomNavigation(route) { vm.screen.value = it } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("studyspace", fontFamily = Serif, fontSize = 24.sp, color = Palette.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(if (account == null) Palette.muted else Palette.sage, androidx.compose.foundation.shape.CircleShape)); Spacer(Modifier.width(7.dp))
                    Text(if (account == null) "ON THIS DEVICE" else status.uppercase(), fontFamily = Sans, fontSize = 9.sp, letterSpacing = 1.sp, color = Palette.muted)
                }
            }
            if (!ready) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.sage, modifier = Modifier.size(26.dp), strokeWidth = 2.dp) }; return@Column }
            if (workspace.state.exams.isNotEmpty()) ExamSwitcher(workspace, vm)
            if (workspace.state.sample) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("SAMPLE WORKSPACE", selected = true); Spacer(Modifier.width(10.dp)); Body("Start fresh in Settings", Modifier.clickable { vm.screen.value = Screen.SETTINGS })
            }
            if (failure.isNotBlank()) Row(Modifier.fillMaxWidth().background(Palette.danger.copy(alpha = .08f)).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) { Body(failure, Modifier.weight(1f), Palette.danger, 3); IconAction("Dismiss message", Icons.Outlined.Close) { vm.repo.error.value = "" } }
            Box(Modifier.weight(1f)) {
                when (route) {
                    Screen.TODAY -> TodayScreen(workspace, vm)
                    Screen.SYLLABUS -> SyllabusScreen(workspace, vm)
                    Screen.LOG -> LogScreen(workspace, vm)
                    Screen.INSIGHTS -> InsightsScreen(workspace, vm)
                    Screen.REVISION -> RevisionScreen(workspace, vm)
                    Screen.MOCKS -> MocksScreen(workspace, vm)
                    Screen.PLAN -> PlanScreen(workspace, vm)
                    Screen.SETTINGS -> SettingsScreen(workspace, vm)
                    Screen.MORE -> MoreScreen(vm)
                }
            }
        }
    }
    if (editor != null) NativeEditor(editor!!, workspace, vm)
}

@Composable private fun ExamSwitcher(w: Workspace, vm: NativeViewModel) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 20.dp, vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Layers, null, tint = Palette.sage, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(10.dp))
            Text(w.exam?.name ?: "All exam workspaces", fontFamily = Sans, fontSize = 12.sp, color = Palette.text, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.UnfoldMore, null, tint = Palette.muted, modifier = Modifier.size(17.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = Palette.raised) {
            DropdownMenuItem(text = { Body("All exam workspaces", color = Palette.text) }, onClick = { vm.select("all"); open = false })
            w.state.exams.forEach { exam -> DropdownMenuItem(text = { Body(exam.name + if (exam.archived) " · archived" else "", color = Palette.text) }, onClick = { vm.select(exam.id); open = false }) }
            DropdownMenuItem(text = { Body("+ Add an exam", color = Palette.sage) }, onClick = { vm.open(Editor.ExamForm()); open = false })
        }
    }
}
@Composable private fun BottomNavigation(screen: Screen, onSelect: (Screen) -> Unit) {
    val entries = listOf(Triple(Screen.TODAY, "Today", Icons.Outlined.Home), Triple(Screen.SYLLABUS, "Syllabus", Icons.Outlined.Checklist), Triple(Screen.LOG, "Log", Icons.Outlined.AddCircleOutline), Triple(Screen.INSIGHTS, "Insights", Icons.Outlined.BarChart), Triple(Screen.MORE, "More", Icons.Outlined.MoreHoriz))
    Column(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding()) {
        HorizontalDivider(color = Palette.border)
        Row(Modifier.fillMaxWidth().height(69.dp)) {
            entries.forEach { (route, label, icon) ->
                val selected = screen == route || route == Screen.MORE && screen in listOf(Screen.REVISION, Screen.MOCKS, Screen.PLAN, Screen.SETTINGS)
                Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(route) }.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, label, tint = if (selected) Palette.sage else Palette.muted, modifier = Modifier.size(22.dp))
                    Text(label, fontFamily = Sans, fontSize = 10.sp, color = if (selected) Palette.sage else Palette.muted)
                }
            }
        }
    }
}
@Composable private fun MoreScreen(vm: NativeViewModel) {
    val links = listOf(Triple(Screen.REVISION, "Return to what matters", Icons.Outlined.History), Triple(Screen.MOCKS, "Scores, sections & mistakes", Icons.Outlined.Assessment), Triple(Screen.PLAN, "Exams, targets & reflections", Icons.Outlined.EventNote), Triple(Screen.SETTINGS, "Sync, backups & app updates", Icons.Outlined.Settings))
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("Your workspace", "A place for everything.") }
        links.forEach { (route, detail, icon) -> item(key = route.name) { Card(Modifier.clickable { vm.screen.value = route }) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Palette.sage, modifier = Modifier.size(23.dp)); Spacer(Modifier.width(15.dp)); Column(Modifier.weight(1f)) { Text(route.title, color = Palette.text, fontFamily = Sans, fontSize = 16.sp); Body(detail) }; Icon(Icons.Outlined.ChevronRight, null, tint = Palette.muted) } } } }
        item { Action("Undo last change", secondary = true, icon = Icons.Outlined.Undo) { vm.perform(false) { vm.repo.undo() } } }
        item { Body("Made for steady progress. Native Android · ${BuildConfig.VERSION_NAME}") }
    }
}
