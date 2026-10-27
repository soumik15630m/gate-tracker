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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun LogSyllabusCreator(w: Workspace, vm: NativeViewModel, exam: String, selected: String?, onSelect: (String) -> Unit) {
    val index = rememberExamIndex(w, exam)
    var creating by rememberSaveable(exam) { mutableStateOf(false) }
    var parent by rememberSaveable(exam) { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var lectures by rememberSaveable { mutableStateOf("") }
    var pace by rememberSaveable { mutableStateOf("1") }
    var mode by rememberSaveable { mutableStateOf("daily") }
    var tests by rememberSaveable { mutableStateOf("") }
    var importance by rememberSaveable { mutableStateOf<Int?>(null) }
    fun begin(id: String?) { parent = id; pace="1";mode="daily"; name = ""; lectures = ""; tests = ""; importance = null; creating = true }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("+ New subject", secondary = true, enabled = exam.isNotBlank()) { begin(null) }
        Action("+ Child under selected topic", secondary = true, enabled = selected != null) { begin(selected) }
        if (creating) Card {
            Body(if (parent == null) "New subject · 1 lecture per study day by default" else "New child inside ${index.path(parent)}")
            fun save() = vm.perform(false) {
                val id = uid("topic")
                vm.repo.edit("Added to syllabus and selected for this session") { state ->
                    val ancestor = state.topics.firstOrNull { it.id == parent && it.examId == exam }
                    require(parent == null || ancestor != null) { "The parent was removed. Choose another parent." }
                    val total = if (parent == null) lectures.ifBlank { "0" }.toIntOrNull() ?: error("Enter a whole lecture total.") else null
                    val testTotal = if (parent == null) tests.ifBlank { "0" }.toIntOrNull() ?: error("Enter a whole test total.") else null
                    val order = (state.topics.filter { it.examId == exam && it.parentId == parent }.maxOfOrNull { it.order } ?: -1.0) + 1
                    listOf(mutation("topic", Topic(id, exam, parent, name.trim(), order, lectureCount = total, testCount = testTotal, importance = importance, schedulePace=if(parent==null) pace.toIntOrNull() ?: error("Enter a whole lecture pace.") else null,paceMode=mode))) + if (ancestor?.completedOn != null) listOf(mutation("topic", ancestor.copy(completedOn = null))) else emptyList()
                }
                onSelect(id); creating = false
            }
            Field(if (parent == null) "New subject name" else "New topic name", name, { name = it }, autoFocus = true, onSubmit = ::save)
            ImportancePicker(importance) { importance = it }
            if (parent == null) { PaceFields(pace,mode,{pace=it},{mode=it}); Field("Total lectures", lectures, { lectures = it }, numeric = true); Field("Total tests", tests, { tests = it }, numeric = true) }
            Body("Add and select, then add another child to go deeper. Your time entry stays open.")
            Action("Add & select", onClick = ::save)
            Action("Cancel creation", secondary = true) { creating = false }
        }
    }
}

@Composable fun LogTopicPath(index: TopicIndex, selected: String?, onSelect: (String?) -> Unit) {
    val path = remember(index, selected) {
        val nodes = mutableListOf<Topic>(); val seen = HashSet<String>(); var node = index.byId[selected]
        while (node != null && seen.add(node.id)) { nodes.add(node); node = index.byId[node.parentId] }; nodes.reversed()
    }
    Choice("Subject", path.firstOrNull()?.id.orEmpty(), listOf("" to "Whole exam") + index.children[null].orEmpty().map { it.id to it.title }) { onSelect(it.ifEmpty { null }) }
    path.forEachIndexed { i, parent -> key(parent.id) {
        val children = index.children[parent.id].orEmpty()
        if (children.isNotEmpty()) Choice("Inside ${parent.title}", path.getOrNull(i + 1)?.id.orEmpty(), listOf("" to "All of ${parent.title}") + children.map { it.id to it.title }) { onSelect(it.ifEmpty { parent.id }) }
    } }
}

@Composable fun LogLecturePicker(subject: Topic?, used: Set<Int>, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    var expanded by rememberSaveable(subject?.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("LECTURES COMPLETED THIS SESSION · ${selected.size}", Palette.teal)
        Body(subject?.let { "${it.title} · ${used.size} completed" + (if((it.lectureCount ?: 0)>0) " / ${it.lectureCount}" else " · total not set") } ?: "Choose a subject or any topic inside it.")
        if ((subject?.lectureCount ?: 0) > 0) {
            Action(if (expanded) "Hide lecture list" else "Choose completed lectures", secondary = true) { expanded = !expanded }
            if (expanded) LazyColumn(Modifier.fillMaxWidth().height(220.dp).testTag("Lecture number list")) {
                items((1..subject!!.lectureCount!!).toList(), key = { it }) { n ->
                    fun toggle() { if (n !in used) onChange(if (n in selected) selected - n else selected + n) }
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable(enabled = n !in used, role = Role.Checkbox) { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(n in used || n in selected, { toggle() }, enabled = n !in used)
                        Body("Lecture $n", Modifier.weight(1f), if (n in used) Palette.muted else Palette.text)
                        if (n in used) Body("Already credited")
                    }
                }
            }
        } else if (subject != null) Field("Lectures completed · total not set",selected.size.toString(),{ text -> text.toIntOrNull()?.takeIf { it in 0..100 }?.let { n -> onChange((1..10000).asSequence().filter { it !in used }.take(n).toSet()) } },numeric=true)
    }
}
