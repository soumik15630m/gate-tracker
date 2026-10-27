package org.studyspace.app

import java.time.LocalDate
import kotlin.math.ceil

data class ScheduleSummary(val subjectId: String, val remaining: Int?, val scheduled: Int, val missingDeadline: Boolean, val rangeTooLong: Boolean, val overbooked: Int = 0)
data class ScheduleResult(val writes: List<Mutation>, val summaries: List<ScheduleSummary>)
fun automaticPlanId(subject: String, date: String): String {
    var h = 14695981039346656037uL
    subject.forEach { h = (h xor it.code.toULong()) * 1099511628211uL }
    return "auto_${h.toString(36)}_$date"
}
fun buildSchedule(state: StudySnapshot, day: String): ScheduleResult {
    val index = TopicIndex(state.topics)
    val writes = mutableListOf<Mutation>(); val summaries = mutableListOf<ScheduleSummary>()
    var liveTotal=state.records.count { !it.deleted }
    val credited = state.sessions.filter { it.lectureSubjectId != null }.groupBy { it.lectureSubjectId }.mapValues { (_, rows) -> rows.flatMap { it.lectureIds }.toSet() }
    val daily = state.sessions.groupBy { it.lectureSubjectId to it.date }.mapValues { (_, rows) -> rows.flatMap { it.lectureIds }.distinct().size }
    fun done(id: String?, date: String) = daily[id to date] ?: 0
    for (exam in state.exams.filterNot { it.archived }) {
        val settings = exam.schedule ?: Schedule(day)
        val end = if(settings.end.isNotBlank() && exam.date.isNotBlank()) minOf(settings.end,exam.date) else settings.end.ifBlank { exam.date }
        val roots = index.children[null].orEmpty().filter { it.examId == exam.id && (it.schedulePace ?: 0) > 0 }.sortedWith(compareBy<Topic> { it.order }.thenBy { it.id })
        if (roots.isEmpty()) continue
        val start = maxOf(settings.start,day)
        val removed = state.tasks.filter { it.examId == exam.id && it.automatic && !it.locked && !it.done && it.date >= day && done(it.topicId,it.date) == 0 }.map { it.id }.toSet()
        liveTotal-=removed.size
        val fixed = state.tasks.filter { it.lectureTarget != null && it.id !in removed }
        val overbooked=mutableMapOf<String,Int>()
        val remaining = roots.associate { root ->
            val count = (index.content(root.id).lectures + credited[root.id].orEmpty()).size
            val reserved = fixed.filter { it.topicId == root.id && it.date >= day }.sumOf { ((it.lectureTarget ?: 0) - done(root.id,it.date)).coerceAtLeast(0) }
            overbooked[root.id]=if((root.lectureCount ?: 0)>0) (count+reserved-root.lectureCount!!).coerceAtLeast(0) else 0
            root.id to if ((root.lectureCount ?: 0) > 0) (root.lectureCount!!-count-reserved).coerceAtLeast(0) else Int.MAX_VALUE
        }.toMutableMap()
        val generated = mutableListOf<StudyTask>(); val weekly = mutableMapOf<String,Int>()
        val tooLong = end.isNotBlank() && dayDifference(start,end) > 730
        if (end.isNotBlank() && end >= start && !tooLong) {
            val dates = (0..dayDifference(start,end)).map { addDays(start,it) }.filter { LocalDate.parse(it).dayOfWeek.value % 7 in settings.weekdays && it !in settings.restDates }
            val weeks = dates.groupBy(::weekStart)
            for (date in dates) {
                var room = settings.dailyLimit?.let { (it-fixed.filter { t -> t.examId==exam.id && t.date==date }.sumOf { t -> t.lectureTarget ?: 0 }).coerceAtLeast(0) } ?: 10000
                val wanted = mutableMapOf<String,Int>()
                for (root in roots) {
                    if (fixed.any { it.topicId==root.id && it.date==date }) continue
                    var quota = root.schedulePace ?: 0
                    if (root.paceMode=="weekly") {
                        val key = root.id+weekStart(date)
                        val reserved = fixed.filter { it.topicId==root.id && weekStart(it.date)==weekStart(date) }.sumOf { it.lectureTarget ?: 0 }
                        val budget = (quota-reserved-(weekly[key] ?: 0)).coerceAtLeast(0)
                        val available = weeks[weekStart(date)].orEmpty().count { d -> d>=date && fixed.none { it.topicId==root.id && it.date==d } }
                        quota = ceil(budget.toDouble()/available.coerceAtLeast(1)).toInt()
                    }
                    wanted[root.id] = minOf(quota,remaining.getValue(root.id))
                }
                val allocations = mutableMapOf<String,Int>()
                while (room>0 && wanted.values.any { it>0 }) for (root in roots) if (room>0 && (wanted[root.id] ?: 0)>0) {
                    allocations[root.id]=(allocations[root.id] ?: 0)+1; wanted[root.id]=wanted.getValue(root.id)-1; room--
                }
                for (root in roots) {
                    val n=allocations[root.id] ?: continue
                    if (remaining[root.id]!=Int.MAX_VALUE) remaining[root.id]=remaining.getValue(root.id)-n
                    val key=root.id+weekStart(date); weekly[key]=(weekly[key] ?: 0)+n
                    val old=state.tasks.firstOrNull { it.topicId==root.id && it.date==date && it.id in removed }
                    require(++liveTotal<=20000) { "A workspace supports up to 20,000 records. Shorten the planning window or disable automatic scheduling for some subjects." }
                    generated.add((old ?: StudyTask(automaticPlanId(root.id,date),exam.id,"${root.title} lectures","plan",date,topicId=root.id)).copy(title="${root.title} lectures",lectureTarget=n,automatic=true,locked=false,originalDate=old?.originalDate ?: date,originalTarget=old?.originalTarget ?: n))
                }
            }
        }
        roots.forEach { root -> summaries.add(ScheduleSummary(root.id,remaining[root.id]?.takeIf { it!=Int.MAX_VALUE },generated.filter { it.topicId==root.id }.sumOf { it.lectureTarget ?: 0 },end.isBlank(),tooLong,overbooked[root.id] ?: 0)) }
        val generatedIds=generated.map { it.id }.toSet()
        state.tasks.filter { it.id in removed && it.id !in generatedIds }.forEach { writes.add(mutation("task",it,true)) }
        generated.filter { it != state.tasks.firstOrNull { old -> old.id==it.id } }.forEach { writes.add(mutation("task",it)) }
    }
    state.tasks.filter { it.automatic && !it.locked && !it.done && it.date>=day && state.topics.none { t -> t.id==it.topicId && (t.schedulePace ?: 0)>0 } && done(it.topicId,it.date)==0 }.forEach { writes.add(mutation("task",it,true)) }
    return ScheduleResult(writes,summaries)
}
fun scheduledChanges(before: List<WireRecord>, changes: List<Mutation>): List<Mutation> {
    val records=before.associateBy { it.id }.toMutableMap()
    changes.forEach { records[it.id]=WireRecord(it.id,it.kind,it.data,deleted=it.deleted) }
    val state=StudySnapshot(records.values.toList())
    val preferences=if(records.values.none { !it.deleted && it.kind=="preferences" } && state.topics.any { (it.schedulePace ?: 0)>0 }) listOf(mutation("preferences",state.preferences)) else emptyList()
    return (changes+preferences+buildSchedule(state,state.today).writes).associateBy { it.id }.values.toList()
}
data class LectureForecast(val done: Int, val remaining: Int?, val recent: Int, val finish: String?)
fun lectureForecast(root: Topic, state: StudySnapshot, day: String, index: TopicIndex = TopicIndex(state.topics)): LectureForecast {
    val entries=state.sessions.filter { it.lectureSubjectId==root.id }
    val done=(index.content(root.id).lectures+entries.flatMap { it.lectureIds }).size
    val recent=entries.filter { it.date>=addDays(day,-27) && it.date<=day }.flatMap { it.lectureIds }.distinct().size
    val remaining=root.lectureCount?.takeIf { it>0 }?.let { (it-done).coerceAtLeast(0) }
    return LectureForecast(done,remaining,recent,if (remaining!=null && remaining>0 && recent>=3) addDays(day,ceil(remaining*28.0/recent).toLong()) else null)
}
data class PracticeStats(val correct: Long, val incorrect: Long, val skipped: Long) {
    val attempted get()=correct+incorrect
    val accuracy get()=if (attempted==0L) null else (correct*100.0/attempted).toInt()
}
fun practiceStats(sessions: List<StudySession>)=PracticeStats(sessions.sumOf { (it.practice?.correct ?: 0).toLong() },sessions.sumOf { (it.practice?.incorrect ?: 0).toLong() },sessions.sumOf { (it.practice?.skipped ?: 0).toLong() })
