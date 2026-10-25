package org.studyspace.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ContentMeters(index: TopicIndex, id: String) {
    val topic = index.byId[id] ?: return
    val subject = index.subjects[id] ?: return
    val progress by produceState(ContentProgress(), index, id) { value = withContext(Dispatchers.Default) { index.content(id) } }
    if ((subject.lectureCount ?: 0) > 0) {
        Body(if (topic.parentId == null) "${progress.lectures.size} / ${subject.lectureCount} lectures completed" else "${progress.lectures.size} lectures recorded in this branch", color = Palette.teal)
        if (topic.parentId == null) Meter(progress.lectures.size * 100 / subject.lectureCount!!.coerceAtLeast(1))
    }
    if ((subject.testCount ?: 0) > 0) {
        Body(if (topic.parentId == null) "${progress.tests.size} / ${subject.testCount} tests completed" else "${progress.tests.size} tests recorded in this branch", color = Palette.sage)
        if (topic.parentId == null) Meter(progress.tests.size * 100 / subject.testCount!!.coerceAtLeast(1), color = Palette.sage)
    }
}

@Composable fun CompletionEditor(editor: Editor.Completion, w: Workspace, vm: NativeViewModel) {
    val topic = w.state.topics.firstOrNull { it.id == editor.id } ?: return
    val index = rememberExamIndex(w, topic.examId)
    val subject = index.subjects[topic.id]
    var kind by rememberSaveable { mutableStateOf("lecture") }
    var lectures by rememberSaveable { mutableStateOf(topic.lectureIds.toSet()) }
    var tests by rememberSaveable { mutableStateOf(topic.testIds.toSet()) }
    val used by produceState(ContentProgress(), index, subject, topic.id) {
        value = withContext(Dispatchers.Default) {
            if (subject == null) ContentProgress() else {
                val others = index.branch(subject.id).filter { it != topic.id }.mapNotNull(index.byId::get)
                ContentProgress(others.flatMap { it.lectureIds }.toSet(), others.flatMap { it.testIds }.toSet())
            }
        }
    }
    val selected = if (kind == "lecture") lectures else tests
    val elsewhere = if (kind == "lecture") used.lectures else used.tests
    val count = (if (kind == "lecture") subject?.lectureCount else subject?.testCount) ?: 0
    EditorFrame(if (editor.recordOnly) "Record your progress." else "A little further.", vm, if (editor.recordOnly) "Save progress" else "Complete & save progress", onSave = {
        vm.perform { vm.repo.edit("Progress saved") { current ->
            val tree = TopicIndex(current.topics); val latest = tree.byId[topic.id] ?: error("This topic was removed.")
            if (editor.recordOnly) listOf(mutation("topic", latest.copy(lectureIds = lectures.sorted(), testIds = tests.sorted())))
            else { require(tree.progress[topic.id]?.closedOn == null) { "This topic was already completed on another device. Reopen it to change its completion." }; tree.completionChanges(topic.id, current.today, lectures.sorted(), tests.sorted()) }
        } }
    }) {
        Body(topic.title, color = Palette.text)
        Body(if (editor.recordOnly) "Update the numbered lectures and tests covered here." else "This branch will close today. Existing closing dates stay as recorded. Select the lectures or tests you covered.")
        Body("Totals belong to ${subject?.title ?: "your subject"}. Chapters and subtopics have no fixed counts.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Chip("Lectures · ${lectures.size}", kind == "lecture") { kind = "lecture" }; Chip("Tests · ${tests.size}", kind == "test") { kind = "test" } }
        if (count == 0) Body("Set the $kind count in your subject’s details to select numbered items. You can still complete this topic now and record content later.")
        else {
            Body("${selected.size} selected here · ${elsewhere.size} recorded elsewhere")
            LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                items((1..count).toList(), key = { it }) { number ->
                    val disabled = number in elsewhere && number !in selected
                    fun toggle() { if (!disabled) { val next = if (number in selected) selected - number else selected + number; if (kind == "lecture") lectures = next else tests = next } }
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable(enabled = !disabled, role = Role.Checkbox) { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(number in selected || number in elsewhere, { toggle() }, enabled = !disabled)
                        Body("${if (kind == "lecture") "Lecture" else "Test"} $number", Modifier.weight(1f), if (disabled) Palette.muted else Palette.text)
                        if (disabled) Body("Recorded elsewhere")
                    }
                }
            }
        }
    }
}

@Composable fun QuickRevisionEditor(w: Workspace, vm: NativeViewModel) {
    var exam by rememberSaveable { mutableStateOf(w.examId.orEmpty()) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var date by rememberSaveable { mutableStateOf(w.day) }
    val index = rememberExamIndex(w, exam)
    EditorFrame("Add a revision.", vm, "Schedule revision", onSave = {
        vm.perform { vm.repo.edit("Revision scheduled") { state ->
            val selected = state.topics.firstOrNull { it.id == topic && it.examId == exam } ?: error("Choose a subject, chapter or subtopic.")
            listOf(mutation("topic", selected.copy(reviewOn = date)))
        } }
    }) {
        ExamChoice(w, exam) { exam = it; topic = null }
        TopicSelector("Subject, chapter or subtopic", index, topic, { topic = it }, importanceFilterable = true)
        DateField("Revision date", date, { date = it })
    }
}
