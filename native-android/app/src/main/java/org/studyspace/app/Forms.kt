@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.studyspace.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable fun NativeEditor(editor: Editor, w: Workspace, vm: NativeViewModel) {
    ModalBottomSheet(onDismissRequest = vm::dismiss, containerColor = Palette.background, contentColor = Palette.text, dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(34.dp, 4.dp).background(Palette.border, androidx.compose.foundation.shape.CircleShape)) }) {
        when (editor) {
            is Editor.ExamForm -> ExamEditor(editor, vm)
            is Editor.TopicForm -> TopicEditor(editor, w, vm)
            is Editor.LogForm -> SessionEditor(editor, w, vm)
            is Editor.ImportOutline -> OutlineEditor(editor, w, vm)
            is Editor.TopicDetail -> TopicDetails(editor.id, w, vm)
            is Editor.MoveTopic -> MoveEditor(editor.id, w, vm)
            is Editor.Review -> ReviewEditor(editor.id, w, vm)
            is Editor.MockForm -> MockEditor(editor, w, vm)
            is Editor.TaskForm -> TaskEditor(editor, w, vm)
            is Editor.ReflectionForm -> ReflectionEditor(editor, w, vm)
            is Editor.SignIn -> SignInEditor(vm)
            is Editor.Delete -> Confirmation("Remove this ${editor.kind}?", if (editor.kind == "topic") "The topic and its child topics will be removed. Logged time keeps its saved topic path." else if (editor.kind == "exam") "This removes the exam and all its topics, study entries, mocks, milestones, and reflections." else "This record will be removed. You can undo the change from More.", vm, "Remove") {
                vm.repo.edit("Removed") { state ->
                    val ids = when (editor.kind) { "exam" -> state.records.filter { it.id == editor.id || it.data["examId"]?.toString()?.trim('"') == editor.id }.map { it.id }.toSet(); "topic" -> TopicIndex(state.topics).branch(editor.id); "mock" -> state.sessions.filter { it.mockId == editor.id }.map { it.id }.toSet() + editor.id; else -> setOf(editor.id) }
                    state.records.filter { it.id in ids && !it.deleted }.map { Mutation(it.kind, it.data, true) }
                }
            }
            is Editor.Fresh -> Confirmation("Begin again, gently.", "A recovery point is saved first. This clears the current workspace. If you are signed in, the removal also syncs to your other devices.", vm, "Start fresh") { vm.repo.startFresh() }
            is Editor.RestoreBackup -> Confirmation("Restore ${editor.count} records?", "The backup replaces this workspace’s current records. A recovery point is saved first. Account changes sync to your other devices.", vm, "Restore backup") { vm.repo.restore(editor.source) }
        }
    }
}
@Composable private fun EditorFrame(title: String, vm: NativeViewModel, saveLabel: String = "Save", onSave: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.workingError.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().fillMaxHeight(.91f).imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontFamily = Serif, fontSize = 29.sp, color = Palette.text, modifier = Modifier.weight(1f))
            if (onDelete != null) IconAction("Delete record", Icons.Outlined.DeleteOutline, Palette.danger, onDelete)
            IconAction("Close editor", Icons.Outlined.Close) { vm.dismiss() }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        if (error.isNotBlank()) Body(error, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), Palette.danger)
        if (onSave != null) {
            HorizontalDivider(color = Palette.border)
            Action(if (busy) "Saving…" else saveLabel, Modifier.fillMaxWidth().padding(20.dp), enabled = !busy, onClick = onSave)
        }
    }
}
@Composable private fun Confirmation(title: String, detail: String, vm: NativeViewModel, button: String, action: suspend () -> Unit) {
    EditorFrame(title, vm, button, onSave = { vm.perform { action() } }) { Body(detail, color = Palette.text); Body("Your sign-in credentials are not affected.") }
}
@Composable private fun ExamEditor(editor: Editor.ExamForm, vm: NativeViewModel) {
    val original = editor.value
    val id = rememberSaveable { original?.id ?: uid("exam") }
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(original?.date ?: addDays(today(), 106)) }
    var target by rememberSaveable { mutableStateOf(original?.weeklyTarget?.let(::formatScore) ?: "20") }
    var description by rememberSaveable { mutableStateOf(original?.description.orEmpty()) }
    var archived by rememberSaveable { mutableStateOf(original?.archived ?: false) }
    EditorFrame(if (original == null) "A new chapter." else "Your exam workspace.", vm, onSave = {
        vm.perform { val hours = target.toDoubleOrNull() ?: error("Enter a weekly target in hours."); vm.repo.commit(listOf(mutation("exam", Exam(id, name.trim(), date, hours, archived, description.trim()))), "Exam saved"); vm.select(id) }
    }, onDelete = original?.let { { vm.open(Editor.Delete("exam", id)) } }) {
        Field("Exam name", name, { name = it }, hint = "GATE 2027")
        DateField("Exam date", date, { date = it })
        Field("Weekly study target · hours", target, { target = it }, numeric = true)
        Field("A note to yourself", description, { description = it }, multiline = true)
        if (original != null) ToggleLine("Archive this exam", archived, { archived = it })
    }
}
@Composable private fun rememberExamIndex(w: Workspace, examId: String): TopicIndex {
    val index by produceState(TopicIndex(emptyList()), w.state.topics, w.state.sessions, examId) { value = withContext(Dispatchers.Default) { TopicIndex(w.state.topics.filter { it.examId == examId }, w.state.sessions.filter { it.examId == examId }) } }
    return index
}
@Composable fun TopicSelector(label: String, index: TopicIndex, selected: String?, onChange: (String?) -> Unit, excluded: Set<String> = emptySet(), rootLabel: String = "General study · no topic") {
    var open by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<VisibleTopic>>(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Body(label)
        Row(Modifier.fillMaxWidth().background(Palette.raised, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { open = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(index.byId[selected]?.title ?: if (selected != null) "Loading selected topic…" else rootLabel, fontFamily = Sans, fontSize = 14.sp, color = Palette.text, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(18.dp))
        }
    }
    if (open) {
        LaunchedEffect(index, query, excluded) { delay(100); nodes = withContext(Dispatchers.Default) { index.visible(index.byId.keys, query).filterNot { it.topic.id in excluded } } }
        Dialog({ open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(18.dp).background(Palette.panel, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Choose a syllabus level", color = Palette.text, fontFamily = Serif, fontSize = 25.sp, modifier = Modifier.weight(1f)); IconAction("Close topic picker", Icons.Outlined.Close) { open = false } }
                Field("Search topics", query, { query = it }, hint = "Any subject, topic, or subtopic")
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 12.dp)) {
                    item(key = "root") { Body(rootLabel, Modifier.fillMaxWidth().clickable { onChange(null); open = false }.padding(vertical = 17.dp), Palette.sage) }
                    items(nodes, key = { it.topic.id }, contentType = { "topic-choice" }) { row ->
                        Column(Modifier.fillMaxWidth().clickable { onChange(row.topic.id); open = false }.padding(start = minOf(row.depth * 9, 36).dp, top = 13.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.topic.title, color = if (row.topic.id == selected) Palette.sage else Palette.text, fontFamily = Sans, fontSize = 14.sp)
                            if (row.depth > 0) Body("Level ${row.depth + 1} · ${index.byId[row.topic.parentId]?.title.orEmpty()}", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun ExamChoice(w: Workspace, value: String, onChange: (String) -> Unit) { Choice("Exam workspace", value, w.state.exams.map { it.id to it.name }, onChange) }
@Composable private fun TopicEditor(editor: Editor.TopicForm, w: Workspace, vm: NativeViewModel) {
    val original = editor.value
    var exam by rememberSaveable { mutableStateOf(original?.examId ?: w.state.topics.firstOrNull { it.id == editor.parent }?.examId ?: w.examId.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(original?.title.orEmpty()) }
    var confidence by rememberSaveable { mutableStateOf(original?.confidence ?: "unrated") }
    var review by rememberSaveable { mutableStateOf(original?.reviewOn.orEmpty()) }
    var focus by rememberSaveable { mutableStateOf(original?.focusOn == w.day) }
    val id = rememberSaveable { original?.id ?: uid("topic") }
    val index = rememberExamIndex(w, exam)
    EditorFrame(if (original == null) "One more small step." else "Edit this topic.", vm, onSave = {
        vm.perform { vm.repo.edit("Topic saved") { current ->
            val parent = original?.parentId ?: editor.parent
            val order = original?.order ?: ((current.topics.filter { it.examId == exam && it.parentId == parent }.maxOfOrNull { it.order } ?: -1.0) + 1)
            val topic = (original ?: Topic(id, exam, parent, title.trim(), order)).copy(title = title.trim(), confidence = confidence, reviewOn = review.ifBlank { null }, focusOn = if (focus) current.today else null)
            listOf(mutation("topic", topic))
        } }
    }) {
        if (original == null && editor.parent == null) ExamChoice(w, exam) { exam = it }
        editor.parent?.let { index.byId[it]?.let { parent -> Body("Inside ${parent.title}", color = Palette.sage) } }
        Field("Topic title", title, { title = it }, hint = "Subject, chapter, or the smallest subtopic")
        Choice("Confidence", confidence, confidenceLabels.toList()) { confidence = it }
        DateField("Revision date", review, { review = it }, allowEmpty = true)
        ToggleLine("Add to today’s focus", focus, { focus = it })
        Body("Completion dates are recorded automatically when you check an item off.")
    }
}
@Composable private fun SessionEditor(editor: Editor.LogForm, w: Workspace, vm: NativeViewModel) {
    val original = editor.value
    var exam by rememberSaveable { mutableStateOf(original?.examId ?: w.state.topics.firstOrNull { it.id == editor.topic }?.examId ?: w.examId.orEmpty()) }
    var topic by rememberSaveable { mutableStateOf(original?.topicId ?: editor.topic) }
    var hours by rememberSaveable { mutableStateOf(((original?.minutes ?: 60) / 60).toString()) }
    var minutes by rememberSaveable { mutableStateOf(((original?.minutes ?: 60) % 60).toString()) }
    var date by rememberSaveable { mutableStateOf(original?.date ?: w.day) }
    var activity by rememberSaveable { mutableStateOf(original?.activity ?: "Learning") }
    var note by rememberSaveable { mutableStateOf(original?.note.orEmpty()) }
    val id = rememberSaveable { original?.id ?: uid("session") }
    val index = rememberExamIndex(w, exam)
    EditorFrame("Time, well spent.", vm, onSave = {
        vm.perform {
            val h = hours.toIntOrNull() ?: error("Enter whole hours."); val m = minutes.toIntOrNull() ?: error("Enter whole minutes.")
            require(h >= 0 && m in 0..59 && h * 60 + m in 1..1440) { "Log between 1 minute and 24 hours, with minutes from 0 to 59." }
            val path = withContext(Dispatchers.Default) { TopicIndex(w.state.topics.filter { it.examId == exam }).path(topic).takeLast(10000) }
            vm.repo.commit(listOf(mutation("session", StudySession(id, exam, topic, if (topic != null && path.isBlank()) original?.topicPath.orEmpty() else path, date, h * 60 + m, activity, note.trim(), original?.mockId))), "Study time logged")
        }
    }, onDelete = original?.let { { vm.open(Editor.Delete("session", id)) } }) {
        ExamChoice(w, exam) { exam = it; topic = null }
        TopicSelector("Subject or any syllabus level", index, topic, { topic = it })
        DateField("Study date", date, { date = it })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Field("Hours", hours, { hours = it }, Modifier.weight(1f), numeric = true); Field("Minutes", minutes, { minutes = it }, Modifier.weight(1f), numeric = true) }
        Choice("Activity", activity, activityLabels.map { it to it }) { activity = it }
        Field("A note about this session", note, { note = it }, multiline = true, hint = "What did you work through?")
    }
}
@Composable private fun OutlineEditor(editor: Editor.ImportOutline, w: Workspace, vm: NativeViewModel) {
    var exam by rememberSaveable { mutableStateOf(w.state.topics.firstOrNull { it.id == editor.parent }?.examId ?: w.examId.orEmpty()) }
    var source by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<OutlineLine>?>(null) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    EditorFrame("Bring the whole syllabus.", vm, if (preview == null) "Preview outline" else "Import ${preview!!.size} topics", onSave = {
        if (preview == null) vm.perform(false) { val parsed = withContext(Dispatchers.Default) { parseOutline(source) }; require(parsed.isNotEmpty()) { "Paste at least one topic." }; preview = parsed }
        else vm.perform { val lines = preview!!; vm.repo.edit("${lines.size} topics imported") { current ->
            val offset = (current.topics.filter { it.examId == exam && it.parentId == editor.parent }.maxOfOrNull { it.order } ?: -1.0) + 1
            outlineTopics(lines, exam, editor.parent, offset).map { mutation("topic", it) }
        } }
    }) {
        if (editor.parent == null) ExamChoice(w, exam) { exam = it }
        Body("Paste an indented outline. Each extra indentation level becomes a child topic. Tabs and consistent spaces work; there is no fixed nesting depth.")
        Field("Indented syllabus", source, { source = it; preview = null }, multiline = true, hint = "Engineering Mathematics\n  Calculus\n    Integration\n      Definite integrals")
        preview?.let { lines ->
            Label("IMPORT PREVIEW · ${lines.size} TOPICS", Palette.sage)
            lines.take(35).forEach { line -> Text(line.title, fontFamily = Sans, color = Palette.text, fontSize = 13.sp, modifier = Modifier.padding(start = minOf(line.depth * 10, 60).dp)) }
            if (lines.size > 35) Body("And ${lines.size - 35} more topics. The full outline is imported.")
        }
        if (busy) Body("Preparing your syllabus…")
    }
}
@Composable private fun TopicDetails(id: String, w: Workspace, vm: NativeViewModel) {
    val topic = w.state.topics.firstOrNull { it.id == id }
    if (topic == null) { EditorFrame("Topic unavailable", vm) { Body("This topic may have been removed on another device.") }; return }
    val index = rememberExamIndex(w, topic.examId)
    val progress = index.progress[id] ?: Progress()
    EditorFrame(topic.title, vm) {
        Body(index.path(id).takeLast(700))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("${progress.done}/${progress.total} final items"); Body(duration(progress.minutes), color = Palette.teal) }; Meter(progress.percent)
        progress.closedOn?.let { Chip("Closed ${displayDate(it)}", selected = true) }
        Choice("Confidence", topic.confidence, confidenceLabels.toList()) { value -> vm.save(listOf(mutation("topic", topic.copy(confidence = value))), "Confidence updated", false) }
        DateField("Revision date", topic.reviewOn.orEmpty(), { value -> vm.save(listOf(mutation("topic", topic.copy(reviewOn = value.ifBlank { null }))), "Revision date updated", false) }, allowEmpty = true)
        ToggleLine("Today’s focus", topic.focusOn == w.day) { focus -> vm.save(listOf(mutation("topic", topic.copy(focusOn = if (focus) w.day else null))), "Focus updated", false) }
        Action(if (progress.closedOn == null) "Complete this branch" else "Reopen this branch", Modifier.fillMaxWidth(), secondary = true) { vm.toggleTopic(id) }
        Action("Log time here", Modifier.fillMaxWidth(), icon = Icons.Outlined.AddCircleOutline) { vm.open(Editor.LogForm(topic = id)) }
        Action("Add a subtopic", Modifier.fillMaxWidth(), secondary = true) { vm.open(Editor.TopicForm(parent = id)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Action("Edit topic", Modifier.weight(1f), secondary = true) { vm.open(Editor.TopicForm(topic)) }; Action("Move branch", Modifier.weight(1f), secondary = true) { vm.open(Editor.MoveTopic(id)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Action("Move up", Modifier.weight(1f), secondary = true) { moveSibling(vm, id, -1) }; Action("Move down", Modifier.weight(1f), secondary = true) { moveSibling(vm, id, 1) } }
        Action("Remove this branch", Modifier.fillMaxWidth(), secondary = true) { vm.open(Editor.Delete("topic", id)) }
    }
}
private fun moveSibling(vm: NativeViewModel, id: String, direction: Int) = vm.perform(false) { vm.repo.edit("Topic order updated") { state ->
    val topic = state.topics.first { it.id == id }; val siblings = state.topics.filter { it.examId == topic.examId && it.parentId == topic.parentId }.sortedBy { it.order }.toMutableList()
    val from = siblings.indexOfFirst { it.id == id }; val to = from + direction
    if (to !in siblings.indices) emptyList() else { val other = siblings[to]; siblings[to] = siblings[from]; siblings[from] = other; siblings.mapIndexedNotNull { i, node -> if (node.order == i.toDouble()) null else mutation("topic", node.copy(order = i.toDouble())) } }
} }
@Composable private fun MoveEditor(id: String, w: Workspace, vm: NativeViewModel) {
    val topic = w.state.topics.firstOrNull { it.id == id } ?: return
    val index = rememberExamIndex(w, topic.examId)
    var parent by rememberSaveable { mutableStateOf(topic.parentId) }
    val excluded = remember(index, id) { index.branch(id) }
    EditorFrame("Give this branch a new home.", vm, onSave = { vm.perform { vm.repo.edit("Branch moved") { state ->
        val current = state.topics.first { it.id == id }; val tree = TopicIndex(state.topics.filter { it.examId == current.examId })
        require(tree.canMove(id, parent)) { "A branch cannot be moved inside itself." }
        val order = (state.topics.filter { it.examId == current.examId && it.parentId == parent }.maxOfOrNull { it.order } ?: -1.0) + 1
        listOf(mutation("topic", current.copy(parentId = parent, order = order)))
    } } }) {
        Body(topic.title, color = Palette.text)
        TopicSelector("New parent", index, parent, { parent = it }, excluded, "Top-level subject")
        Body("All descendants move with this topic. Time and confidence stay attached to their original records.")
    }
}
@Composable private fun ReviewEditor(id: String, w: Workspace, vm: NativeViewModel) {
    val topic = w.state.topics.firstOrNull { it.id == id } ?: return
    var confidence by rememberSaveable { mutableStateOf(topic.confidence) }
    var next by rememberSaveable { mutableStateOf(addDays(w.day, 7)) }
    EditorFrame("Remember a little longer.", vm, "Mark reviewed", onSave = { vm.save(listOf(mutation("topic", topic.copy(confidence = confidence, reviewOn = next.ifBlank { null }, lastReviewedOn = w.day))), "Review recorded") }) {
        Text(topic.title, fontFamily = Sans, color = Palette.text, fontSize = 17.sp)
        Choice("Confidence after this review", confidence, confidenceLabels.toList()) { confidence = it }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) { listOf(3, 7, 14).forEach { days -> Chip("+$days days", next == addDays(w.day, days.toLong())) { next = addDays(w.day, days.toLong()) } } }
        DateField("Next review", next, { next = it }, allowEmpty = true)
        Body("This records a review date. Log study time separately if you want it counted in your effort metrics.")
    }
}
@Composable private fun TaskEditor(editor: Editor.TaskForm, w: Workspace, vm: NativeViewModel) {
    val original = editor.value
    var exam by rememberSaveable { mutableStateOf(original?.examId ?: w.examId.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(original?.title.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(original?.date.orEmpty()) }
    val id = rememberSaveable { original?.id ?: uid("task") }
    EditorFrame(if (editor.type == "inbox") "Make room in your mind." else "A meaningful checkpoint.", vm, onSave = { vm.save(listOf(mutation("task", StudyTask(id, exam, title.trim(), editor.type, date, original?.done ?: false, original?.topicId))), "Note saved") }, onDelete = original?.let { { vm.open(Editor.Delete("task", id)) } }) {
        ExamChoice(w, exam) { exam = it }
        Field(if (editor.type == "inbox") "Inbox note" else "Milestone title", title, { title = it }, multiline = true)
        DateField("Target date", date, { date = it }, allowEmpty = true)
    }
}
@Composable private fun ReflectionEditor(editor: Editor.ReflectionForm, w: Workspace, vm: NativeViewModel) {
    val original = editor.value
    var exam by rememberSaveable { mutableStateOf(original?.examId ?: w.examId.orEmpty()) }
    var week by rememberSaveable { mutableStateOf(original?.week ?: weekStart(w.day)) }
    var worked by rememberSaveable { mutableStateOf(original?.worked.orEmpty()) }
    var improve by rememberSaveable { mutableStateOf(original?.improve.orEmpty()) }
    var next by rememberSaveable { mutableStateOf(original?.next.orEmpty()) }
    val id = rememberSaveable { original?.id ?: uid("reflection") }
    EditorFrame("Pause. Notice. Continue.", vm, onSave = { vm.save(listOf(mutation("reflection", Reflection(id, exam, weekStart(week), worked.trim(), improve.trim(), next.trim()))), "Weekly reflection saved") }, onDelete = original?.let { { vm.open(Editor.Delete("reflection", id)) } }) {
        ExamChoice(w, exam) { exam = it }; DateField("Week beginning", week, { week = weekStart(it) })
        Field("What worked this week?", worked, { worked = it }, multiline = true)
        Field("What would you improve?", improve, { improve = it }, multiline = true)
        Field("Next week’s focus", next, { next = it }, multiline = true)
    }
}
@Composable private fun SignInEditor(vm: NativeViewModel) {
    var server by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_SERVER.ifBlank { "http://10.0.2.2:3001" }) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    EditorFrame("Your work, across devices.", vm, if (register) "Create account" else "Sign in", onSave = { vm.perform { vm.repo.signIn(server, email, password, register) } }) {
        Body("Use the same server and account on Android and the desktop website. A new account starts empty; you can restore a local backup into it.")
        Field("Sync server address", server, { server = it }, hint = "https://your-site.vercel.app")
        Field("Email", email, { email = it })
        Field("Password", password, { password = it }, password = true)
        ToggleLine("Create a new account", register) { register = it }
        Body("Use a password with at least 10 characters. Public servers require HTTPS; private LAN addresses are supported for self-hosting.")
    }
}

data class SectionDraft(val id: String = uid(), val name: String = "", val score: String = "", val maximum: String = "")
data class MistakeDraft(val id: String = uid(), val topicId: String? = null, val category: String = "Concept gap", val note: String = "")
class MockDraft(mock: MockTest?) {
    val id = mock?.id ?: uid("mock")
    var exam by mutableStateOf(mock?.examId.orEmpty())
    var name by mutableStateOf(mock?.name.orEmpty())
    var series by mutableStateOf(mock?.series.orEmpty())
    var date by mutableStateOf(mock?.date.orEmpty())
    var score by mutableStateOf(mock?.score?.let(::formatScore).orEmpty())
    var maximum by mutableStateOf(mock?.maximum?.let(::formatScore) ?: "100")
    var minutes by mutableStateOf(mock?.minutes?.toString() ?: "180")
    var correct by mutableStateOf(mock?.correct?.toString().orEmpty())
    var incorrect by mutableStateOf(mock?.incorrect?.toString().orEmpty())
    var unattempted by mutableStateOf(mock?.unattempted?.toString().orEmpty())
    var logTime by mutableStateOf(mock == null)
    val sections = mutableStateListOf<SectionDraft>().apply { addAll(mock?.sections.orEmpty().map { SectionDraft(name = it.name, score = formatScore(it.score), maximum = formatScore(it.maximum)) }) }
    val mistakes = mutableStateListOf<MistakeDraft>().apply { addAll(mock?.mistakes.orEmpty().map { MistakeDraft(topicId = it.topicId, category = it.category, note = it.note) }) }
}
@Composable private fun MockEditor(editor: Editor.MockForm, w: Workspace, vm: NativeViewModel) {
    val draft = remember(editor) { (vm.mockDraft ?: MockDraft(editor.value).also { vm.mockDraft = it }).also { if (it.exam.isBlank()) it.exam = w.examId.orEmpty(); if (it.date.isBlank()) it.date = w.day; if (editor.value != null) it.logTime = w.state.sessions.any { session -> session.mockId == it.id } } }
    val index = rememberExamIndex(w, draft.exam)
    fun parseOptional(value: String): Int? = if (value.isBlank()) null else value.toIntOrNull() ?: error("Answer counts must be whole numbers.")
    EditorFrame("Learn from the checkpoint.", vm, onSave = { vm.perform {
        val result = MockTest(draft.id, draft.exam, draft.name.trim(), draft.series.trim(), draft.date, draft.score.toDoubleOrNull() ?: error("Enter a valid score."), draft.maximum.toDoubleOrNull() ?: error("Enter the maximum score."), draft.minutes.toIntOrNull() ?: error("Enter whole minutes."), parseOptional(draft.correct), parseOptional(draft.incorrect), parseOptional(draft.unattempted), draft.sections.map { MockSection(it.name.trim(), it.score.toDoubleOrNull() ?: error("Enter every section’s score."), it.maximum.toDoubleOrNull() ?: error("Enter every section’s maximum.")) }, draft.mistakes.map { Mistake(it.topicId, it.category, it.note.trim()) })
        vm.repo.edit("Mock result saved") { state ->
            val changes = mutableListOf(mutation("mock", result))
            val linked = state.sessions.filter { it.mockId == result.id }
            if (draft.logTime) {
                changes.add(mutation("session", StudySession(linked.firstOrNull()?.id ?: uid("session"), result.examId, date = result.date, minutes = result.minutes, activity = "Mock test", note = result.name, mockId = result.id)))
                changes.addAll(linked.drop(1).map { mutation("session", it, true) })
            } else changes.addAll(linked.map { mutation("session", it, true) })
            changes
        }
    } }, onDelete = editor.value?.let { { vm.open(Editor.Delete("mock", draft.id)) } }) {
        ExamChoice(w, draft.exam) { draft.exam = it; draft.mistakes.replaceAll { mistake -> mistake.copy(topicId = null) } }
        Field("Mock name", draft.name, { draft.name = it })
        Field("Comparable test series", draft.series, { draft.series = it }, hint = "Full syllabus · same test format")
        DateField("Test date", draft.date, { draft.date = it })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Field("Score", draft.score, { draft.score = it }, Modifier.weight(1f), numeric = true); Field("Maximum", draft.maximum, { draft.maximum = it }, Modifier.weight(1f), numeric = true) }
        Field("Duration · minutes", draft.minutes, { draft.minutes = it }, numeric = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("Correct", draft.correct, { draft.correct = it }, Modifier.weight(1f), numeric = true); Field("Incorrect", draft.incorrect, { draft.incorrect = it }, Modifier.weight(1f), numeric = true); Field("Unattempted", draft.unattempted, { draft.unattempted = it }, Modifier.weight(1f), numeric = true) }
        ToggleLine("Include this test in logged study time", draft.logTime, { draft.logTime = it })
        SectionTitle("Section scores", "Optional, up to 200 sections")
        if (draft.sections.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(draft.sections, key = { it.id }) { section ->
                fun update(next: SectionDraft) { val i = draft.sections.indexOfFirst { it.id == section.id }; if (i >= 0) draft.sections[i] = next }
                Card { Field("Section name", section.name, { update(section.copy(name = it)) }); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Section score", section.score, { update(section.copy(score = it)) }, Modifier.weight(1f), numeric = true); Field("Section maximum", section.maximum, { update(section.copy(maximum = it)) }, Modifier.weight(1f), numeric = true) }; Action("Remove section", secondary = true) { draft.sections.removeAll { it.id == section.id } } }
            }
        }
        Action("Add section", secondary = true, enabled = draft.sections.size < 200) { draft.sections.add(SectionDraft()) }
        SectionTitle("Mistake journal", "A useful note, not a judgement.")
        if (draft.mistakes.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(draft.mistakes, key = { it.id }) { mistake ->
                fun update(next: MistakeDraft) { val i = draft.mistakes.indexOfFirst { it.id == mistake.id }; if (i >= 0) draft.mistakes[i] = next }
                Card { Choice("Mistake category", mistake.category, mistakeLabels.map { it to it }) { update(mistake.copy(category = it)) }; TopicSelector("Linked topic", index, mistake.topicId, { update(mistake.copy(topicId = it)) }); Field("What went wrong?", mistake.note, { update(mistake.copy(note = it)) }, multiline = true); Action("Remove mistake", secondary = true) { draft.mistakes.removeAll { it.id == mistake.id } } }
            }
        }
        Action("Add mistake", secondary = true, enabled = draft.mistakes.size < 300) { draft.mistakes.add(MistakeDraft()) }
    }
}
