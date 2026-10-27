package org.studyspace.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable private data class TopicDraft(val id: String = uid("topic"), val title: String = "", val importance: Int? = null, val lectures: String = "", val tests: String = "", val pace: String = "1", val mode: String = "daily")

@Composable fun TopicBatchEditor(editor: Editor.TopicForm, w: Workspace, vm: NativeViewModel) {
    var exam by rememberSaveable { mutableStateOf(w.state.topics.firstOrNull { it.id == editor.parent }?.examId ?: w.examId.orEmpty()) }
    var rows by rememberSaveable(stateSaver = listSaver<List<TopicDraft>, String>(save = { items -> items.map { codec.encodeToString(it) } }, restore = { items -> items.map { codec.decodeFromString<TopicDraft>(it) } })) { mutableStateOf(listOf(TopicDraft())) }
    var active by rememberSaveable { mutableStateOf(rows.first().id) }
    val parent = w.state.topics.firstOrNull { it.id == editor.parent }
    val noun = if (editor.parent == null) "subject" else "child topic"
    fun update(id: String, change: (TopicDraft) -> TopicDraft) { rows = rows.map { if (it.id == id) change(it) else it } }
    fun saveAll() = vm.perform {
        vm.repo.edit("${rows.size} ${if (rows.size == 1) noun else noun + "s"} added") { state ->
            require(editor.parent == null || state.topics.any { it.id == editor.parent && it.examId == exam }) { "The parent is no longer available. Reopen the form." }
            val offset = (state.topics.filter { it.examId == exam && it.parentId == editor.parent }.maxOfOrNull { it.order } ?: -1.0) + 1
            val changes = rows.mapIndexed { i, row ->
                require(row.title.isNotBlank()) { "Give $noun ${i + 1} a name, or remove that row." }
                val lectures = if (editor.parent == null) row.lectures.ifBlank { "0" }.toIntOrNull() ?: error("Use a whole lecture count for subject ${i + 1}.") else null
                val tests = if (editor.parent == null) row.tests.ifBlank { "0" }.toIntOrNull() ?: error("Use a whole test count for subject ${i + 1}.") else null
                mutation("topic", Topic(row.id, exam, editor.parent, row.title.trim(), offset + i, lectureCount = lectures, testCount = tests, importance = row.importance, schedulePace=if(editor.parent==null) row.pace.toIntOrNull() ?: error("Enter a whole lecture pace.") else null,paceMode=row.mode))
            }.toMutableList()
            state.topics.firstOrNull { it.id == editor.parent }?.let { changes.add(mutation("topic", it.copy(completedOn = null))) }
            changes
        }
    }
    EditorFrame(if (parent == null) "Add subjects." else "Add child topics.", vm, "Save all / ${rows.size}", onSave = ::saveAll) {
        if (editor.parent == null) ExamChoice(w, exam) { exam = it }
        Body(if (parent == null) "New subjects start at 1 lecture per study day; adjust in subject details or Plan. Add rows for separate subjects, then save them together. Enter saves the list." else "All rows become children of ${parent.title}. Keep names visible while adding more rows; Save all or Enter creates them together.")
        rows.forEachIndexed { index, row -> key(row.id) {
            Card {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label("${noun.uppercase()} ${index + 1}", modifier = Modifier.weight(1f))
                    if (rows.size > 1) IconAction("Remove $noun ${index + 1}", Icons.Outlined.DeleteOutline) {
                        rows = rows.filterNot { it.id == row.id }; active = rows[maxOf(0, index - 1).coerceAtMost(rows.lastIndex)].id
                    }
                }
                Field(if (index == 0) "Topic title" else "Topic title ${index + 1}", row.title, { title -> update(row.id) { it.copy(title = title) } }, autoFocus = row.id == active, focusKey = active, onSubmit = ::saveAll)
                ImportancePicker(row.importance) { importance -> update(row.id) { it.copy(importance = importance) } }
                if (editor.parent == null) PaceFields(row.pace,row.mode,{value -> update(row.id) { it.copy(pace=value) }},{value -> update(row.id) { it.copy(mode=value) }})
                if (editor.parent == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Lecture count", row.lectures, { value -> update(row.id) { it.copy(lectures = value) } }, Modifier.weight(1f), numeric = true)
                    Field("Test count", row.tests, { value -> update(row.id) { it.copy(tests = value) } }, Modifier.weight(1f), numeric = true)
                }
            }
        } }
        Action(if (editor.parent == null) "+ Add subject" else "+ Add child", Modifier.fillMaxWidth(), secondary = true, icon = Icons.Outlined.Add, enabled = rows.size < 100) { val next = TopicDraft(); rows = rows + next; active = next.id }
    }
}
