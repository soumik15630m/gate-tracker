package org.studyspace.app

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val exam = Exam("exam", "GATE", "2027-02-07")
    private fun topic(id: String, parent: String? = null, date: String? = null) = Topic(id, exam.id, parent, id, completedOn = date)
    @Test fun `coverage uses leaves and closing dates roll up without rewriting old dates`() {
        val topics = listOf(topic("subject"), topic("chapter", "subject"), topic("one", "chapter", "2026-10-20"), topic("two", "chapter"))
        val index = TopicIndex(topics)
        assertEquals(2, index.total.total); assertEquals(1, index.total.done)
        val changes = index.completionChanges("chapter", "2026-10-25").map { it.data.asModel<Topic>() }.associateBy { it.id }
        assertFalse(changes.containsKey("one")); assertEquals("2026-10-25", changes["two"]?.completedOn)
        assertEquals("2026-10-25", changes["subject"]?.completedOn)
        val completed = topics.map { changes[it.id] ?: it }
        val reopened = TopicIndex(completed).completionChanges("chapter", "2026-10-26")
        assertTrue(reopened.all { it.data.asModel<Topic>().completedOn == null })
    }
    @Test fun `study time at every level counts once`() {
        val topics = listOf(topic("subject"), topic("child", "subject"))
        val sessions = listOf(StudySession("one", exam.id, "subject", date = "2026-10-25", minutes = 20), StudySession("two", exam.id, "child", date = "2026-10-25", minutes = 30), StudySession("three", exam.id, date = "2026-10-25", minutes = 10))
        val tree = TopicIndex(topics, sessions)
        assertEquals(50, tree.progress["subject"]?.minutes); assertEquals(30, tree.progress["child"]?.minutes); assertEquals(60, tree.total.minutes)
    }
    @Test fun `streak is live before today's study and excludes future entries`() {
        val entries = listOf("2026-10-23", "2026-10-24", "2026-10-26").mapIndexed { i, day -> StudySession("s$i", exam.id, date = day, minutes = 25) }
        assertEquals(Streak(2, 2, 2), streak(entries, "2026-10-25", 20))
        assertEquals(0, streak(entries, "2026-10-25", 30).current)
        assertEquals("2026-10-19", weekStart("2026-10-25"))
    }
    @Test fun `outline parsing supports deep consistent indentation and rejects malformed alignment`() {
        val source = (0 until 180).joinToString("\n") { " ".repeat(it * 2) + "Topic $it" }
        val lines = parseOutline(source); assertEquals(179, lines.last().depth)
        val topics = outlineTopics(lines, exam.id)
        val index = TopicIndex(topics)
        assertEquals(1, index.total.total); assertEquals(180, index.visible(topics.map { it.id }.toSet()).size)
        assertFalse(index.canMove(topics.first().id, topics.last().id))
        assertTrue(index.canMove(topics.last().id, null))
        assertThrows(IllegalArgumentException::class.java) { parseOutline("Subject\n    Topic\n  Misaligned") }
    }
    @Test fun `ten thousand deep nodes are indexed iteratively without stack overflow`() {
        val topics = (0 until 10000).map { Topic("t$it", exam.id, if (it == 0) null else "t${it - 1}", "Topic $it") }
        val start = System.nanoTime()
        val index = TopicIndex(topics)
        validateRelationships(listOf(WireRecord(exam.id, "exam", jsonOf(exam))) + topics.map { WireRecord(it.id, "topic", jsonOf(it)) })
        assertEquals(10000, index.preorder.size); assertEquals(1, index.total.total)
        assertEquals(10000, index.branch("t0").size)
        assertEquals(10000, index.visible(emptySet(), "Topic 9999").size)
        println("10,000-deep index + graph validation: ${(System.nanoTime() - start) / 1000000}ms on test JVM")
    }
    @Test fun `backup round trip preserves shared web schema and independent confidence`() {
        val topic = topic("one").copy(order = 1792866000000.0, confidence = "confident")
        val mock = MockTest("mock", exam.id, "Mock", "Full", "2026-10-25", -1.0, sections = listOf(MockSection("Math", -1.0, 25.0)), mistakes = listOf(Mistake(topic.id, "Concept gap", "Revisit this")))
        val records = listOf(BackupRecord("exam", jsonOf(exam)), BackupRecord("topic", jsonOf(topic)), BackupRecord("mock", jsonOf(mock)))
        val serialized = codec.encodeToString(Backup.serializer(), Backup(records = records))
        assertEquals(records, readBackup(serialized))
        assertNull(topic.completedOn); assertEquals("confident", topic.confidence)
        assertThrows(IllegalArgumentException::class.java) { readBackup(codec.encodeToString(Backup.serializer(), Backup(records = records + records.first()))) }
        assertThrows(IllegalArgumentException::class.java) { validate("session", jsonOf(StudySession("bad", exam.id, date = "2026-02-30", minutes = 25))) }
        validate("exam", jsonOf(exam.copy(description = null)))
        assertNull(jsonOf(exam.copy(description = null)).asModel<Exam>().description)
        assertThrows(IllegalArgumentException::class.java) { validate("topic", jsonOf(topic.copy(completedOn = ""))) }
        assertThrows(IllegalArgumentException::class.java) { validate("topic", jsonOf(topic.copy(reviewOn = " "))) }
    }
    @Test fun `circular and cross exam topic parents are rejected`() {
        val nodes = listOf(topic("one", "two"), topic("two", "one"))
        assertThrows(IllegalArgumentException::class.java) { validateRelationships(listOf(WireRecord(exam.id, "exam", jsonOf(exam))) + nodes.map { WireRecord(it.id, "topic", jsonOf(it)) }) }
    }
    @Test fun `csv neutralizes spreadsheet formulas`() {
        assertEquals("\"'=2+2\",\"a\"\"b\"", csv(listOf(listOf("=2+2", "a\"b"))))
    }
    @Test fun `public authentication requires HTTPS while private LAN addresses remain available`() {
        assertEquals("https://gate.example.com", serverAddress(" https://gate.example.com/ "))
        assertEquals("http://192.168.1.20:3001", serverAddress("http://192.168.1.20:3001"))
        assertThrows(IllegalArgumentException::class.java) { serverAddress("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { serverAddress("https://user:password@example.com") }
        assertThrows(IllegalArgumentException::class.java) { serverAddress("https://example.com/api") }
    }
}
