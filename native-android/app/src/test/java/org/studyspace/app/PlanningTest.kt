package org.studyspace.app

import org.junit.Assert.*
import org.junit.Test

class PlanningTest {
    @Test fun `automatic calendar stops at the workspace record limit`() {
        val initial=state(tasks=(0 until 19998).map { StudyTask("note_$it",exam.id,"Note") })
        assertThrows(IllegalArgumentException::class.java) { buildSchedule(initial,day) }
    }
    private val day="2026-10-27"
    private val exam=Exam("exam","Study goal","2026-11-09",schedule=Schedule(day,weekdays=listOf(0,1,2,3,4,5,6)))
    private val root=Topic("networks",exam.id,title="Networks",lectureCount=8,schedulePace=2)
    private fun state(exam: Exam=this.exam, roots: List<Topic> = listOf(root), tasks: List<StudyTask> = emptyList(), sessions: List<StudySession> = emptyList()) = StudySnapshot(listOf(WireRecord(exam.id,"exam",jsonOf(exam)))+roots.map { WireRecord(it.id,"topic",jsonOf(it)) }+tasks.map { WireRecord(it.id,"task",jsonOf(it)) }+sessions.map { WireRecord(it.id,"session",jsonOf(it)) })
    private fun apply(state: StudySnapshot,writes: List<Mutation>): StudySnapshot {
        val records=state.records.associateBy { it.id }.toMutableMap(); writes.forEach { records[it.id]=WireRecord(it.id,it.kind,it.data,deleted=it.deleted) }; return StudySnapshot(records.values.toList())
    }
    @Test fun `automatic totals grow and shrink without regenerating identical records`() {
        val initial=state();val result=apply(initial,buildSchedule(initial,day).writes)
        assertEquals(4,result.tasks.size);assertTrue(buildSchedule(result,day).writes.isEmpty())
        val grown=state(roots=listOf(root.copy(lectureCount=12)),tasks=result.tasks)
        assertEquals(12,apply(grown,buildSchedule(grown,day).writes).tasks.sumOf { it.lectureTarget ?: 0 })
        val smaller=state(roots=listOf(root.copy(lectureCount=5)),tasks=result.tasks)
        assertEquals(5,apply(smaller,buildSchedule(smaller,day).writes).tasks.sumOf { it.lectureTarget ?: 0 })
        assertEquals("auto_21xf82z53bjsy_2026-10-27",automaticPlanId(root.id,day))
    }
    @Test fun `shared capacity and rest dates enforce real deadline shortfalls`() {
        val initial=state(exam.copy(date="2026-10-29",schedule=Schedule(day,weekdays=listOf(0,1,2,3,4,5,6),restDates=listOf("2026-10-28"),dailyLimit=2)),listOf(root,root.copy(id="math",title="Math",order=1.0)))
        val result=buildSchedule(initial,day);val targets=apply(initial,result.writes).tasks
        assertEquals(4,targets.size);assertTrue(targets.all { it.date!="2026-10-28" && it.lectureTarget==1 })
        assertEquals(6,result.summaries.first { it.subjectId==root.id }.remaining)
    }
    @Test fun `weekly budget and unknown totals match web scheduling`() {
        val initial=state(exam.copy(date="2026-11-01"),listOf(root.copy(lectureCount=30,schedulePace=5,paceMode="weekly")))
        assertEquals(5,apply(initial,buildSchedule(initial,day).writes).tasks.sumOf { it.lectureTarget ?: 0 })
        val open=state(exam.copy(date="2026-10-29"),listOf(root.copy(lectureCount=0)))
        assertEquals(3,buildSchedule(open,day).writes.size);assertNull(buildSchedule(open,day).summaries.single().remaining)
        val undated=state(exam.copy(date="",goalType="personal"));validate("exam",jsonOf(undated.exams.single()))
        assertTrue(buildSchedule(undated,day).summaries.single().missingDeadline)
    }
    @Test fun `locked and partially studied days are protected and credit counts once`() {
        val initial=state();val tasks=apply(initial,buildSchedule(initial,day).writes).tasks
        val session=StudySession("log",exam.id,root.id,date=day,minutes=30,lectureIds=listOf(1),lectureSubjectId=root.id)
        val edited=state(roots=listOf(root.copy(lectureCount=10,lectureIds=listOf(1))),tasks=tasks.mapIndexed { i,t -> if(i==1) t.copy(locked=true) else t },sessions=listOf(session))
        val result=apply(edited,buildSchedule(edited,day).writes)
        assertEquals(tasks.first(),result.tasks.first { it.date==day });assertTrue(result.tasks.first { it.id==tasks[1].id }.locked)
        assertEquals(10,result.tasks.sumOf { it.lectureTarget ?: 0 });validateRelationships(result.records)
    }
    @Test fun `question practice and actual lecture forecast stay independent from checklist coverage`() {
        val session=StudySession("log",exam.id,root.id,date=day,minutes=30,lectureIds=listOf(1,2,3,4),lectureSubjectId=root.id,practice=Practice(8,2,3))
        val state=state(sessions=listOf(session));validate("session",jsonOf(session))
        assertEquals(80,practiceStats(listOf(session)).accuracy);assertEquals(3L,practiceStats(listOf(session)).skipped)
        assertEquals("2026-11-24",lectureForecast(root,state,day).finish)
        assertNull(lectureForecast(root.copy(lectureCount=0),state,day).finish)
        assertThrows(IllegalArgumentException::class.java) { validate("session",jsonOf(session.copy(practice=Practice(-1)))) }
        assertFalse(validSchedule(Schedule(day,weekdays=emptyList())))
    }
    @Test fun `catch up reserves available slots and retains partial original backlog`() {
        val old=StudyTask("missed",exam.id,"Missed lectures","plan","2026-10-26",topicId=root.id,lectureTarget=5)
        val initial=state(tasks=listOf(old));val scheduled=apply(initial,buildSchedule(initial,day).writes)
        val moved=apply(scheduled,redistributeMissed(scheduled,day,day,day,exam.id))
        val rebuilt=apply(moved,buildSchedule(moved,day).writes)
        assertTrue(rebuilt.tasks.first { it.date==day }.locked)
        val source=rebuilt.tasks.first { it.id==old.id };assertEquals(5,source.lectureTarget);assertEquals(2,source.carriedCount);assertNull(source.carriedOn)
        assertEquals(8,rebuilt.tasks.filter { it.date>=day }.sumOf { it.lectureTarget ?: 0 })
        validateRelationships(rebuilt.records)
    }

}
