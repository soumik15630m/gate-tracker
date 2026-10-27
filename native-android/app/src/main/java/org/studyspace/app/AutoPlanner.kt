package org.studyspace.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun PaceFields(pace: String, mode: String, onPace: (String)->Unit, onMode: (String)->Unit) {
    Field("Automatic lecture pace · 0 disables",pace,onPace,numeric=true)
    Choice("Distribute this many lectures",mode,listOf("daily" to "Per selected study day","weekly" to "Per week · distribute automatically"),onMode)
    Body("Saving lecture totals updates future targets. Adjust study weekdays, rest days and capacity in Plan.")
}
@Composable fun AutoPlanner(w: Workspace, vm: NativeViewModel) {
    val exam=w.exam ?: return
    var settings by rememberSaveable { mutableStateOf(false) }
    var catchUp by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable { mutableStateOf(w.day) }
    var end by rememberSaveable { mutableStateOf(addDays(w.day,6)) }
    val roots=w.index.children[null].orEmpty()
    val result by produceState<ScheduleResult?>(null,w) { value=withContext(Dispatchers.Default) { buildSchedule(w.state,w.day) } }
    val forecasts by produceState<Map<String,LectureForecast>>(emptyMap(),w) { value=withContext(Dispatchers.Default) { roots.associate { it.id to lectureForecast(it,w.state,w.day) } } }
    val missed=w.tasks.filter { it.lectureTarget!=null && it.date<w.day && !it.locked && it.carriedOn==null && lectureDay(w.sessions,it.topicId.orEmpty(),it.date)<it.lectureTarget }
    Card(title="Your automatic study plan",subtitle="Save lecture totals to update future calendar targets automatically.") {
        Action("Scheduling preferences",secondary=true) { settings=true }
        roots.forEach { root ->
            val summary=result?.summaries?.firstOrNull { it.subjectId==root.id }; val forecast=forecasts[root.id]
            Body(root.title,color=Palette.text)
            Body(if ((root.schedulePace ?: 0)>0) "${root.schedulePace} lectures / ${if(root.paceMode=="weekly") "week" else "study day"}" else "Automatic scheduling off")
            Body(if ((root.lectureCount ?: 0)>0) "${forecast?.done ?: 0} / ${root.lectureCount} lectures completed" else "Open-ended total · scheduled through your deadline")
            if (summary?.missingDeadline==true) Body("Choose an exam date or a planner end date.",color=Palette.gold)
            if (summary?.rangeTooLong==true) Body("Choose a planning window of up to two years.",color=Palette.gold)
            if ((summary?.overbooked ?: 0)>0) Body("Protected targets exceed remaining lectures by ${summary?.overbooked}. Edit or unlock those targets.",color=Palette.gold)
            if ((summary?.remaining ?: 0)>0) Body("${summary?.remaining} lectures do not fit before your deadline. Increase pace, study days or daily capacity.",color=Palette.gold)
            Body(if (forecast?.remaining==0) "All configured lectures completed." else forecast?.finish?.let { "At your actual recent pace: around ${displayDate(it)}. Based on ${forecast.recent} lectures in 28 calendar days." } ?: "A completion forecast appears after 3 logged lectures in 28 days, with a subject total set.")
        }
        if (missed.isNotEmpty()) Action("Catch up · ${missed.size} missed targets",secondary=true) { catchUp=true }
        Body("Past days, days with recorded lectures, and locked targets stay in place. Future targets reflect unfinished lectures.")
    }
    if (settings) ScheduleDialog(w,vm,exam) { settings=false }
    if (catchUp) Dialog({catchUp=false}) { Card(title="Make room to catch up") {
        Body("Available slots in this window are reserved and locked for missed work. Original days stay visible; work that does not fit stays overdue. Daily pace, weekdays and capacity still apply.")
        DateField("Resume from",start,{start=it}); DateField("Plan through",end,{end=it})
        Action("Redistribute remaining lectures") { vm.perform(false) {
            require(start>=w.day && validDate(start) && validDate(end) && end>=start) { "Choose a future date range." }
            vm.repo.edit("Future targets redistributed. Original missed days preserved.") { state ->
                redistributeMissed(state,w.day,start,end,exam.id)
            }; catchUp=false
        } }
        Action("Close",secondary=true) { catchUp=false }
    } }
}
@Composable private fun ScheduleDialog(w: Workspace, vm: NativeViewModel, exam: Exam, onClose: ()->Unit) {
    val saved=exam.schedule ?: Schedule(w.day)
    var start by rememberSaveable { mutableStateOf(saved.start) }
    var end by rememberSaveable { mutableStateOf(saved.end) }
    var days by rememberSaveable { mutableStateOf(saved.weekdays.toSet()) }
    var rests by rememberSaveable { mutableStateOf(saved.restDates.joinToString(", ")) }
    var limit by rememberSaveable { mutableStateOf(saved.dailyLimit?.toString().orEmpty()) }
    val roots=w.index.children[null].orEmpty()
    var paces by rememberSaveable { mutableStateOf(roots.associate { it.id to (it.schedulePace ?: 0).toString() }) }
    var modes by rememberSaveable { mutableStateOf(roots.associate { it.id to it.paceMode }) }
    Dialog(onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(18.dp),title="Your study rhythm") {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                DateField("Start date",start,{start=it})
                DateField("Planning end · optional, no later than exam date",end,{end=it},allowEmpty=true)
                listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat").forEachIndexed { n,label -> ToggleLine(label,n in days,{days=if(it) days+n else days-n}) }
                Field("Rest dates · YYYY-MM-DD separated by commas",rests,{rests=it})
                Field("Maximum total lectures per day · optional",limit,{limit=it},numeric=true)
                roots.forEach { root -> Body(root.title,color=Palette.text); PaceFields(paces[root.id].orEmpty(),modes[root.id] ?: "daily",{paces=paces+(root.id to it)},{modes=modes+(root.id to it)}) }
                Body("Saving rebuilds future unlocked targets immediately. Current progress and past days stay protected. Capacity shortfalls appear beside each subject.")
            }
            Action("Save rhythm & update calendar") { vm.perform(false) {
                val schedule=Schedule(start,end,days.sorted(),rests.split(Regex("[\\s,]+")).filter { it.isNotEmpty() },if(limit.isBlank()) null else limit.toIntOrNull() ?: error("Enter a whole daily capacity."))
                require(validSchedule(schedule)) { "Check dates, study weekdays and daily capacity (1–100)." }
                vm.repo.edit("Study rhythm saved. Future calendar updated.") { state ->
                    val current=state.exams.first { it.id==exam.id }
                    listOf(mutation("exam",current.copy(schedule=schedule)))+state.topics.filter { it.examId==exam.id && it.parentId==null && it.id in paces }.map { mutation("topic",it.copy(schedulePace=paces[it.id]?.toIntOrNull() ?: error("Enter a whole lecture pace."),paceMode=modes[it.id] ?: "daily")) }
                }; onClose()
            } }
            Action("Close",secondary=true,onClick=onClose)
        }
    }
}
