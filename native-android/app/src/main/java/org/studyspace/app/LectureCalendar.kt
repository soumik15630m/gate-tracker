package org.studyspace.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable fun LectureCalendar(w: Workspace, vm: NativeViewModel) {
    var month by rememberSaveable { mutableStateOf(w.day.take(7)) }
    var day by rememberSaveable { mutableStateOf(w.day) }
    var subject by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("1") }
    val plans = w.tasks.filter { it.lectureTarget != null }
    val roots = w.index.children[null].orEmpty()
    val root = roots.firstOrNull { it.id == subject }
    val calendar = YearMonth.parse(month)
    val offset = calendar.atDay(1).dayOfWeek.value - 1
    val slots = List(offset) { null } + (1..calendar.lengthOfMonth()).map { calendar.atDay(it).toString() }
    Card(title = "Your lecture calendar", subtitle = "Set daily subject targets. Logged lectures update each day automatically.") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconAction("Previous planner month", Icons.Outlined.ChevronLeft) { month = calendar.minusMonths(1).toString() }
            Body(calendar.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), Palette.text)
            IconAction("Next planner month", Icons.Outlined.ChevronRight) { month = calendar.plusMonths(1).toString() }
        }
        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) { Label(it) } } }
        slots.chunked(7).forEach { week -> Row(Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                val date = week.getOrNull(i)
                if (date == null) Spacer(Modifier.weight(1f).height(48.dp))
                else {
                    val entries = plans.filter { it.date == date }
                    val complete = entries.isNotEmpty() && entries.all { lectureDay(w.sessions, it.topicId.orEmpty(), date) >= (it.lectureTarget ?: 0) }
                    Column(Modifier.weight(1f).height(48.dp).background(if (day == date) Palette.sage.copy(alpha = .1f) else Palette.background, RoundedCornerShape(8.dp)).border(1.dp, if (day == date) Palette.sage else Palette.background, RoundedCornerShape(8.dp)).semantics { contentDescription = "Planner day $date, ${entries.size} lecture targets" }.clickable(role = Role.Button) { day = date }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(LocalDate.parse(date).dayOfMonth.toString(), fontFamily = Sans, fontSize = 12.sp, color = if (date == w.day) Palette.sage else Palette.text)
                        if (entries.isNotEmpty()) Box(Modifier.padding(top = 4.dp).size(4.dp).background(if (complete) Palette.teal else if (date < w.day) Palette.gold else Palette.sage, RoundedCornerShape(2.dp)))
                    }
                }
            }
        } }
        DateField("Lecture plan day", day, { day = it; month = it.take(7) })
        plans.filter { it.date == day }.forEach { plan ->
            val done = lectureDay(w.sessions, plan.topicId.orEmpty(), day); val total = plan.lectureTarget ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Body(roots.firstOrNull { it.id == plan.topicId }?.title ?: plan.title, color = Palette.text); Body("$done / $total lectures · ${(total - done).coerceAtLeast(0)} remaining", color = if (done >= total) Palette.teal else Palette.sage) }
                IconAction("Edit lecture target", Icons.Outlined.Edit) { subject = plan.topicId.orEmpty(); target = total.toString() }
                IconAction(if(plan.automatic) "Make this a rest day" else "Remove lecture target", Icons.Outlined.DeleteOutline) { vm.perform(false) { vm.repo.edit("Lecture target removed. Automatic planning respects this rest day.") { state -> listOf(mutation("task",plan,true))+if(plan.automatic) state.exams.firstOrNull { it.id==plan.examId }?.let { listOf(mutation("exam",it.copy(schedule=(it.schedule ?: Schedule(w.day)).copy(restDates=((it.schedule?.restDates ?: emptyList())+plan.date).distinct())))) } .orEmpty() else emptyList() } } }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Action(if(plan.locked) "Unlock day" else "Lock day",secondary=true) { vm.save(listOf(mutation("task",plan.copy(locked=!plan.locked))),"Day protection updated",false) }; Action("Log lectures",secondary=true) { vm.open(Editor.LogForm(topic=plan.topicId)) } }
            Body((if(plan.automatic) "Automatic" else "Manual")+(if(plan.locked) " · Locked" else "")+(plan.carriedOn?.let { " · Carried forward ${displayDate(it)}" } ?: ""))
            Meter(if (total == 0) 0 else done * 100 / total)
        }
        Choice("Subject for daily lectures", subject, listOf("" to "Choose a subject") + roots.map { it.id to "${it.title} · ${it.lectureCount ?: 0} total lectures" }) { subject = it }
        Field("Lectures to complete", target, { target = it }, numeric = true)
        root?.let { Body("Maximum ${it.lectureCount ?: 0} lectures in ${it.title}") }
        Action("Save daily lecture target", enabled = root != null) { vm.perform(false) {
            val n = target.toIntOrNull() ?: error("Enter a whole lecture target.")
            vm.repo.edit("Daily lecture target saved") { state ->
                val selected = state.topics.firstOrNull { it.id == subject && it.parentId == null } ?: error("Choose a subject.")
                require(n in 1..(selected.lectureCount?.takeIf { it>0 } ?: 10000)) { "The target must fit this subject's lecture total." }
                val previous = state.tasks.firstOrNull { it.lectureTarget != null && it.topicId == subject && it.date == day }
                listOf(mutation("task", StudyTask(previous?.id ?: uid("task"), selected.examId, "${selected.title} lectures", "plan", day, topicId = subject, lectureTarget = n,automatic=previous?.automatic ?: false,locked=true,originalDate=previous?.originalDate ?: day,originalTarget=previous?.originalTarget ?: n)))
            }
        } }
        LectureReminderOption(vm)
    }
}
