package org.studyspace.app

import java.time.LocalDate

fun redistributeMissed(state: StudySnapshot, day: String, start: String, end: String, examId: String): List<Mutation> {
    require(validDate(start) && validDate(end) && start>=day && dayDifference(start,end) in 0..730) { "Choose a future catch-up window of up to two years." }
    val exam=state.exams.firstOrNull { it.id==examId } ?: error("Choose a study goal.")
    val settings=exam.schedule ?: Schedule(day);val index=TopicIndex(state.topics)
    val tasks=state.tasks.filter { it.examId==examId }.toMutableList();val changes=mutableListOf<Mutation>()
    val missed=tasks.filter { it.lectureTarget!=null && it.date<day && !it.locked && it.carriedOn==null }.sortedBy { it.date }
    for(root in index.children[null].orEmpty().filter { it.examId==examId }) {
        val sources=missed.filter { it.topicId==root.id }
        val backlog=sources.sumOf { ((it.lectureTarget ?: 0)-lectureDay(state.sessions,root.id,it.date)-(it.carriedCount ?: 0)).coerceAtLeast(0) }
        if(backlog==0)continue
        var outstanding=backlog
        val credit=(index.content(root.id).lectures+state.sessions.filter { it.lectureSubjectId==root.id }.flatMap { it.lectureIds }).size
        val reserved=tasks.filter { it.topicId==root.id && it.date>=day && (!it.automatic || it.locked || lectureDay(state.sessions,root.id,it.date)>0) }.sumOf { ((it.lectureTarget ?: 0)-lectureDay(state.sessions,root.id,it.date)).coerceAtLeast(0) }
        var remaining=if((root.lectureCount ?: 0)>0) (root.lectureCount!!-credit-reserved).coerceAtLeast(0) else Int.MAX_VALUE
        for(offset in 0..dayDifference(start,end)) {
            val date=addDays(start,offset)
            if(outstanding==0 || exam.date.isNotBlank() && date>exam.date)break
            if(LocalDate.parse(date).dayOfWeek.value%7 !in settings.weekdays || date in settings.restDates)continue
            val existing=tasks.firstOrNull { it.topicId==root.id && it.date==date && it.lectureTarget!=null }
            if(existing!=null && (!existing.automatic || existing.locked) || lectureDay(state.sessions,root.id,date)>0)continue
            val room=settings.dailyLimit?.let { (it-tasks.filter { t -> t.date==date && t.topicId!=root.id && t.lectureTarget!=null }.sumOf { t -> t.lectureTarget ?: 0 }).coerceAtLeast(0) } ?: 10000
            val pace=root.schedulePace?.takeIf { it>0 } ?: 1
            val quota=if(root.paceMode=="weekly") (pace-tasks.filter { it.topicId==root.id && it.date!=date && it.lectureTarget!=null && weekStart(it.date)==weekStart(date) }.sumOf { it.lectureTarget ?: 0 }).coerceAtLeast(0) else pace
            val count=minOf(outstanding,remaining,room,quota);if(count==0)continue
            val target=minOf(maxOf(existing?.lectureTarget ?: 0,count),remaining,room,quota)
            val next=(existing ?: StudyTask(automaticPlanId(root.id,date),examId,"${root.title} lectures","plan",date,topicId=root.id)).copy(lectureTarget=target,automatic=true,locked=true,originalDate=existing?.originalDate ?: date,originalTarget=existing?.originalTarget ?: target,notes="Catch-up allocation from missed days. $count lectures reserved; original targets preserved.")
            changes.add(mutation("task",next));if(existing!=null)tasks[tasks.indexOf(existing)]=next else tasks.add(next)
            outstanding-=count;if(remaining!=Int.MAX_VALUE)remaining-=target
        }
        var allocated=backlog-outstanding
        for(source in sources) {
            val needed=((source.lectureTarget ?: 0)-lectureDay(state.sessions,root.id,source.date)-(source.carriedCount ?: 0)).coerceAtLeast(0)
            val n=minOf(needed,allocated);if(n==0)continue;allocated-=n
            changes.add(mutation("task",source.copy(carriedCount=(source.carriedCount ?: 0)+n,carriedOn=if(n==needed) day else source.carriedOn,originalDate=source.originalDate ?: source.date,originalTarget=source.originalTarget ?: source.lectureTarget)))
        }
    }
    require(changes.isNotEmpty()) { "No free capacity. Increase pace or daily capacity, select more study days, or unlock a future target." }
    return changes
}
