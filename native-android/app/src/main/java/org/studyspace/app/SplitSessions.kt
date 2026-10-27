package org.studyspace.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

fun equalSplitMinutes(total: Int, count: Int): List<Int> {
    require(total in 1..1440 && count in 1..20 && count <= total) { "Give each entry at least one minute, with up to 20 entries and 24 hours in total." }
    return List(count) { total / count + if (it < total % count) 1 else 0 }
}
fun validateSplitMinutes(total: Int, minutes: List<Int>) {
    require(total in 1..1440 && minutes.size in 1..20 && minutes.all { it in 1..1440 } && minutes.sum() == total) { "Split minutes must add up exactly to this study block, with at least one minute per entry." }
}
@Serializable data class SplitDraft(val id: String = uid("session"), val topicId: String? = null, val minutes: String = "0", val lectureIds: Set<Int> = emptySet(), val questions: Boolean = false, val correct: String = "0", val incorrect: String = "0", val skipped: String = "0", val note: String = "")

fun splitSessionChanges(state: StudySnapshot, entries: List<StudySession>, additions: Map<String, Set<Int>>, reviewOn: String?): List<Mutation> {
    val tree = TopicIndex(state.topics)
    val changed = linkedMapOf<String, Topic>()
    val reserved = mutableMapOf<String, MutableSet<Int>>()
    for (entry in entries) {
        validate("session", jsonOf(entry))
        val topic = entry.topicId?.let { changed[it] ?: tree.byId[it] }
        val added = additions[entry.id].orEmpty()
        if (added.isNotEmpty()) {
            val selected = topic ?: error("The selected topic was removed.")
            val root = tree.subjects[selected.id] ?: error("Choose a subject.")
            val used = reserved.getOrPut(root.id) { (tree.content(root.id).lectures + state.sessions.filter { it.id != entry.id && it.lectureSubjectId == root.id }.flatMap { it.lectureIds }).toMutableSet() }
            require(added.all { it in 1..(root.lectureCount?.takeIf { n -> n > 0 } ?: 10000) && it !in used }) { "Some lectures were already credited or exceed the subject total. Choose remaining lectures." }
            used.addAll(added)
            changed[selected.id] = selected.copy(lectureIds = (selected.lectureIds + added).distinct().sorted())
        }
        if (entry.activity == "Revision" && topic != null && reviewOn != null) {
            val latest = changed[topic.id] ?: topic
            changed[topic.id] = latest.copy(lastReviewedOn = entry.date, reviewOn = reviewOn)
        }
    }
    return entries.map { mutation("session", it) } + changed.values.map { mutation("topic", it) }
}

@Composable fun SplitSessionRows(w: Workspace, vm: NativeViewModel, exam: String, index: TopicIndex, selected: String?, primaryLectures: Set<Int>, total: Int, firstMinutes: String, onFirstMinutes: (String) -> Unit, rows: List<SplitDraft>, onChange: (List<SplitDraft>) -> Unit) {
    fun patch(id: String, update: (SplitDraft) -> SplitDraft) { onChange(rows.map { if (it.id == id) update(it) else it }) }
    Label("ONE STUDY BLOCK · MULTIPLE TOPICS", Palette.sage)
    Body("Split your total time across subjects or subtopics. Each minute counts once; lectures belong to their selected subject.")
    if (rows.isNotEmpty()) Field("Minutes for entry 1", firstMinutes, onFirstMinutes, numeric = true)
    rows.forEachIndexed { i, row -> key(row.id) {
        val subject = index.subjects[row.topicId]
        val used = remember(index, w.state.sessions, rows, selected, primaryLectures, row.id) {
            if (subject == null) emptySet() else index.content(subject.id).lectures + w.state.sessions.filter { it.lectureSubjectId == subject.id }.flatMap { it.lectureIds } + rows.filter { it.id != row.id && index.subjects[it.topicId]?.id == subject.id }.flatMap { it.lectureIds } + if (index.subjects[selected]?.id == subject.id) primaryLectures else emptySet()
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("ENTRY ${i+2}")
            TopicSelector("Find subject or subtopic · entry ${i+2}", index, row.topicId, { id -> patch(row.id) { it.copy(topicId=id,lectureIds=emptySet()) } })
            LogTopicPath(index, row.topicId) { id -> patch(row.id) { it.copy(topicId=id,lectureIds=emptySet()) } }
            LogSyllabusCreator(w, vm, exam, row.topicId) { id -> patch(row.id) { it.copy(topicId=id,lectureIds=emptySet()) } }
            Field("Minutes for entry ${i+2}", row.minutes, { value -> patch(row.id) { it.copy(minutes=value) } }, numeric=true)
            LogLecturePicker(subject, used, row.lectureIds) { value -> patch(row.id) { it.copy(lectureIds=value) } }
            ToggleLine("Question practice · entry ${i+2}",row.questions,{ value -> patch(row.id) { it.copy(questions=value) } })
            if (row.questions) {
                Field("Correct answers · entry ${i+2}",row.correct,{ value -> patch(row.id) { it.copy(correct=value) } },numeric=true)
                Field("Incorrect answers · entry ${i+2}",row.incorrect,{ value -> patch(row.id) { it.copy(incorrect=value) } },numeric=true)
                Field("Skipped questions · entry ${i+2}",row.skipped,{ value -> patch(row.id) { it.copy(skipped=value) } },numeric=true)
            }
            Field("Note · entry ${i+2}",row.note,{ value -> patch(row.id) { it.copy(note=value) } })
            Action("Remove entry ${i+2}", secondary=true) { onChange(rows.filter { it.id != row.id }) }
        }
    } }
    Action("+ Add another subject or topic",secondary=true,enabled=rows.size<19) { if(rows.isEmpty())onFirstMinutes(total.toString());onChange(rows+SplitDraft()) }
    if (rows.isNotEmpty()) {
        Action("Split time equally",secondary=true,enabled=total in (rows.size+1)..1440) { val values=equalSplitMinutes(total,rows.size+1);onFirstMinutes(values[0].toString());onChange(rows.mapIndexed { i,row -> row.copy(minutes=values[i+1].toString()) }) }
        val assigned=(firstMinutes.toIntOrNull() ?: 0)+rows.sumOf { it.minutes.toIntOrNull() ?: 0 }
        Body("$assigned / $total minutes assigned",color=if(assigned==total)Palette.teal else Palette.gold)
    }
}
