package org.studyspace.app

import org.junit.Assert.*
import org.junit.Test

class SplitSessionTest {
    @Test fun `splits conserve the block and reject invented minutes`() {
        assertEquals(listOf(21,20,20),equalSplitMinutes(61,3))
        validateSplitMinutes(60,listOf(15,35,10))
        listOf(listOf(60,60),listOf(0,60),listOf(10,20),listOf(-1,61)).forEach { assertTrue(runCatching { validateSplitMinutes(60,it) }.isFailure) }
    }
    @Test fun `deep topics share lecture limits and duplicate lectures fail the entire split`() {
        val exam=Exam("split-exam","Study","2027-02-07")
        val root=Topic("split-root",exam.id,title="Networks",lectureCount=3)
        val parent=Topic("split-parent",exam.id,root.id,"Switching")
        val leaf=Topic("split-leaf",exam.id,parent.id,"Packet switching")
        val other=Topic("split-other",exam.id,root.id,"Routing")
        val records=listOf(mutation("exam",exam))+listOf(root,parent,leaf,other).map { mutation("topic",it) }
        val state=StudySnapshot(records.map { WireRecord(it.id,it.kind,it.data) })
        val a=StudySession("session_split_a",exam.id,leaf.id,"Networks / Switching / Packet switching","2026-10-27",30,"Revision",lectureIds=listOf(1),lectureSubjectId=root.id,groupId="session_split_a")
        val b=a.copy(id="session_split_b",topicId=other.id,topicPath="Networks / Routing",lectureIds=listOf(2))
        val changes=splitSessionChanges(state,listOf(a,b),mapOf(a.id to setOf(1),b.id to setOf(2)),"2026-11-05")
        assertEquals(60,changes.filter { it.kind=="session" }.sumOf { it.data.asModel<StudySession>().minutes })
        assertEquals(2,changes.count { it.kind=="topic" })
        val updated=changes.first { it.id==leaf.id }.data.asModel<Topic>()
        assertEquals(listOf(1),updated.lectureIds);assertEquals("2026-11-05",updated.reviewOn)
        assertTrue(runCatching { splitSessionChanges(state,listOf(a,b.copy(lectureIds=listOf(1))),mapOf(a.id to setOf(1),b.id to setOf(1)),null) }.isFailure)
        assertTrue(runCatching { splitSessionChanges(state,listOf(a.copy(lectureIds=listOf(4))),mapOf(a.id to setOf(4)),null) }.isFailure)
        val merged=(state.records.associateBy { it.id }+changes.associate { it.id to WireRecord(it.id,it.kind,it.data) }).values.toList()
        validateRelationships(merged)
    }
}
