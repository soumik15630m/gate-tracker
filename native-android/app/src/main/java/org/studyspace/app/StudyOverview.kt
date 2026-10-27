package org.studyspace.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun DailyAgenda(w: Workspace, vm: NativeViewModel) {
    val plans=w.tasks.filter { it.type=="plan" && it.date==w.day }
    val due=w.topics.filter { !it.reviewOn.isNullOrBlank() && it.reviewOn<=w.day }
    Card(title="Your daily agenda",subtitle="Lectures, revision and study blocks in one place.") {
        plans.forEach { plan ->
            val done=if(plan.lectureTarget!=null) lectureDay(w.sessions,plan.topicId.orEmpty(),w.day)>=plan.lectureTarget else plan.done
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(plan.lectureTarget==null) Checkbox(done,{vm.save(listOf(mutation("task",plan.copy(done=it))),"Agenda updated",false)})
                Body((if(done) "✓ " else "")+plan.title,Modifier.weight(1f),if(done) Palette.teal else Palette.text)
            }
            Body(if(plan.lectureTarget!=null) "${lectureDay(w.sessions,plan.topicId.orEmpty(),w.day)} / ${plan.lectureTarget} lectures" else "${duration(plan.minutes ?: 0)} · ${plan.activity}")
            Action("Log ${plan.title}",secondary=true) { vm.open(Editor.LogForm(topic=plan.topicId,initialActivity=plan.activity ?: "Learning")) }
        }
        due.forEach { topic -> Body("${topic.title} · ${if(topic.reviewOn!!<w.day) "Overdue revision" else "Revision due today"}",color=Palette.text); Action("Revise ${topic.title}",secondary=true) { vm.open(Editor.LogForm(topic=topic.id,initialActivity="Revision")) } }
        if(plans.isEmpty() && due.isEmpty()) Body("A clear day. Set a subject’s lecture pace or add a study block in Plan.")
        Action("Adjust plan",secondary=true) { vm.screen.value=Screen.PLAN }
    }
}
@Composable fun StudyEvidence(w: Workspace, vm: NativeViewModel) {
    var importance by rememberSaveable { mutableStateOf("4+") }
    var level by rememberSaveable { mutableStateOf("all") }
    var source by rememberSaveable { mutableStateOf("self") }
    val start=weekStart(w.day); val end=addDays(start,6)
    val entries=remember(w) { w.sessions.filter { it.date>=start && it.date<=w.day } }
    val stats=remember(entries) { practiceStats(entries) }
    val tasks=w.tasks.filter { it.type=="plan" && it.date in start..end }
    val lecturePlans=tasks.filter { it.lectureTarget!=null }
    val planned=lecturePlans.sumOf { it.lectureTarget ?: 0 }
    val credited=lecturePlans.sumOf { minOf(it.lectureTarget ?: 0,lectureDay(w.sessions,it.topicId.orEmpty(),it.date)) }
    var risks by remember { mutableStateOf(emptyList<VisibleTopic>()) }
    LaunchedEffect(w.index,importance,level,source) { risks=withContext(Dispatchers.Default) { w.index.smartSearch.results("",TopicFilter(importance,level,source),20000).filter { it.topic.confidence!="confident" }.take(20) } }
    Card(title="Intentions and actual effort",subtitle="This calendar week") {
        Body("$credited / $planned planned lectures credited to their scheduled days",color=Palette.teal)
        Meter(if(planned==0) 0 else credited*100/planned)
        Body("${duration(entries.sumOf { it.minutes })} logged · ${duration(tasks.filter { it.lectureTarget==null }.sumOf { it.minutes ?: 0 })} in timed study blocks")
        Body("Actual time includes all logged sessions. Lecture counts are kept separate from planned study hours.")
    }
    Card(title="Practice, measured honestly",subtitle="This week · attempted = correct + incorrect") {
        Body("${stats.attempted} attempted · ${stats.accuracy?.let { "$it%" } ?: "—"} accuracy · ${stats.skipped} skipped",color=Palette.teal)
        w.index.children[null].orEmpty().forEach { root ->
            val p=practiceStats(entries.filter { w.index.subjects[it.topicId]?.id==root.id })
            if(p.attempted>0 || p.skipped>0) { Body(root.title,color=Palette.text); Body("${p.correct} correct · ${p.incorrect} incorrect · ${p.skipped} skipped · ${p.accuracy?.let { "$it%" } ?: "—"} accuracy") }
        }
        Body("Add optional question counts when logging study. Coverage, confidence and accuracy stay separate.")
    }
    Card(title="Important topics needing confidence",subtitle="Independent ratings at every depth") {
        TopicFilterControls(TopicFilter(importance,level,source)) { importance=it.importance;level=it.level;source=it.source }
        risks.forEach { row -> Body(row.topic.title,color=Palette.text); Body("${w.index.path(row.topic.parentId)} · ${confidenceLabels[row.topic.confidence]}"); Action("Practice ${row.topic.title}",secondary=true) { vm.open(Editor.LogForm(topic=row.topic.id,initialActivity="Practice")) } }
        if(risks.isEmpty()) Body("No topics match this importance and confidence combination.")
    }
}
