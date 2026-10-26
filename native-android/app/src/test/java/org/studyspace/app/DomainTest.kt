package org.studyspace.app

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun `lecture targets track logged days and missing days without counting planned time`() {
        val root = Topic("lecture-root", "lecture-exam", title = "Networks", lectureCount = 3)
        val plan = StudyTask("lecture-target", root.examId, "Daily lectures", "plan", "2026-10-26", topicId = root.id, lectureTarget = 2)
        val session = StudySession("lecture-entry", root.examId, root.id, root.title, "2026-10-26", 45, lectureIds = listOf(1,2), lectureSubjectId = root.id)
        validate("task", jsonOf(plan)); validate("session", jsonOf(session))
        val records = listOf(mutation("exam", Exam(root.examId, "Exam", "2027-02-07")), mutation("topic", root), mutation("task", plan)).map { WireRecord(it.id,it.kind,it.data) }
        validateRelationships(records)
        assertEquals(0, lectureDay(emptyList(),root.id,plan.date)); assertEquals(2, lectureDay(listOf(session,session),root.id,plan.date))
        assertTrue(missingLectureDays(StudySnapshot(records), "2026-10-26", false).isEmpty())
        assertEquals(2, missingLectureDays(StudySnapshot(records), "2026-10-26", true).single().remaining)
        assertEquals(2, missingLectureDays(StudySnapshot(records), "2026-10-27", false).single().remaining)
        val logged = WireRecord(session.id,"session",jsonOf(session))
        assertTrue(missingLectureDays(StudySnapshot(records+logged), "2026-10-27", false).isEmpty())
        assertTrue(runCatching { validateRelationships(records + logged.copy(data=jsonOf(session.copy(lectureIds=listOf(4))))) }.isFailure)
        assertTrue(runCatching { validateRelationships(records + logged + logged.copy(id="another",data=jsonOf(session.copy(id="another")))) }.isFailure)
    }

    @Test fun `stopwatch excludes pauses restores running time and rounds only when logging`() {
        val paused = StudyTimer("timer", startedAt = 1000).pause(61000)
        assertEquals(60000L, paused.elapsed(500000))
        val running = paused.resume(500000)
        assertEquals(running, running.resume(600000))
        val restored = codec.decodeFromString<StudyTimer>(codec.encodeToString(StudyTimer.serializer(), running))
        val finished = restored.pause(620000)
        assertEquals(180000L, finished.elapsed(999999)); assertEquals(3, finished.minutes())
        assertEquals(finished, finished.pause(999999)); assertEquals("00:03:00", timerClock(finished.elapsed()))
        assertEquals(50L, StudyTimer("clock", 50, 1000).elapsed(0))
        assertEquals(1, StudyTimer("short", 29000).minutes()); assertEquals(2, StudyTimer("round", 90000).minutes())
        assertEquals(1500, StudyTimer("long", 25 * 3600000L).minutes())
    }

    @Test fun `smart search finds nested topics by typo prefix and ancestor context with independent ratings`() {
        val root = Topic("search-root", "exam", title = "Computer Networks", importance = 3)
        val parent = Topic("search-parent", "exam", root.id, "Data communication", importance = 2)
        val leaf = Topic("search-leaf", "exam", parent.id, "Packet switching", importance = 5)
        val na = Topic("search-na", "exam", root.id, "Not rated")
        val index = TopicIndex(listOf(root, parent, leaf, na)); val search = index.smartSearch
        assertEquals(leaf.id, search.results("packet swithcing").first().topic.id)
        assertEquals(leaf.id, search.results("swit pack").first().topic.id)
        assertEquals(leaf.id, search.results("networks packet").first().topic.id)
        assertEquals(listOf(leaf.id), search.results("", TopicFilter("5")).map { it.topic.id })
        assertEquals(listOf(root.id), search.results("", TopicFilter("3", "subject")).map { it.topic.id })
        assertEquals(setOf(leaf.id, na.id), search.results("", TopicFilter("3", "leaf", "subject")).map { it.topic.id }.toSet())
        assertEquals(listOf(leaf.id), search.results("", TopicFilter("2", "leaf", "parent")).map { it.topic.id })
        assertEquals(5, leaf.importance); assertEquals(3, root.importance)
    }
    private val exam = Exam("exam", "GATE", "2027-02-07")
    private fun topic(id: String, parent: String? = null, date: String? = null) = Topic(id, exam.id, parent, id, completedOn = date)
    @Test fun `importance supports independent NA and one to five stars with exact and minimum filters`() {
        val root = topic("root").copy(importance = 5); val child = topic("child", "root")
        assertEquals("N/A", importanceLabel(child.importance)); assertTrue(matchesImportance(child, "na")); assertFalse(matchesImportance(child, "4+"))
        assertTrue(matchesImportance(root, "5")); assertTrue(matchesImportance(root, "4+")); assertFalse(matchesImportance(root, "na"))
        (1..5).forEach { validate("topic", jsonOf(child.copy(importance = it))) }
        listOf(0, -1, 6).forEach { value -> assertThrows(IllegalArgumentException::class.java) { validate("topic", jsonOf(child.copy(importance = value))) } }
        validate("topic", jsonOf(child.copy(importance = null)))
    }
    @Test fun `legacy JSON backups survive unchanged and importance edits preserve prior progress`() {
        val original = topic("legacy", date = "2026-10-24").copy(confidence = "confident", lectureCount = 10, testCount = 2, lectureIds = listOf(1, 2), testIds = listOf(1), reviewOn = "2026-10-26")
        val legacyJson = kotlinx.serialization.json.JsonObject(jsonOf(original).filterKeys { it != "importance" })
        val records = listOf(BackupRecord("exam", jsonOf(exam)), BackupRecord("topic", legacyJson), BackupRecord("session", jsonOf(StudySession("old-session", exam.id, original.id, date = "2026-10-24", minutes = 45))))
        val source = codec.encodeToString(Backup.serializer(), Backup(records = records))
        assertEquals(records, readBackup(source)); assertNull(legacyJson.asModel<Topic>().importance)
        listOf(5, 2, null).forEach { stars ->
            val edited = legacyJson.asModel<Topic>().copy(importance = stars)
            assertEquals(original, edited.copy(importance = null)); assertEquals(stars, jsonOf(edited).asModel<Topic>().importance)
            val backup = Backup(records = records.map { if (it.kind == "topic") it.copy(data = jsonOf(edited)) else it })
            assertEquals(backup.records, readBackup(codec.encodeToString(Backup.serializer(), backup)))
        }
    }
    @Test fun `subject content is derived from numbered allocations and reopening releases it`() {
        val root = topic("root").copy(lectureCount = 10, testCount = 3)
        val one = topic("one", "root", "2026-10-22").copy(lectureIds = listOf(1, 2), testIds = listOf(1))
        val two = topic("two", "root").copy(lectureIds = listOf(2, 3))
        val nodes = listOf(root, one, two); val index = TopicIndex(nodes)
        assertEquals(3, index.content("root").lectures.size)
        assertEquals("root", index.subjects["two"]?.id)
        val changes = index.completionChanges("root", "2026-10-25", listOf(4), listOf(2)).associate { it.id to it.data.asModel<Topic>() }
        val updated = nodes.map { changes[it.id] ?: it }
        assertEquals("2026-10-22", updated.first { it.id == "one" }.completedOn)
        assertEquals(4, TopicIndex(updated).content("root").lectures.size)
        val reopened = TopicIndex(updated).completionChanges("root", "2026-10-26").map { it.data.asModel<Topic>() }
        assertEquals(0, TopicIndex(reopened).content("root").lectures.size)
        assertThrows(IllegalArgumentException::class.java) { validate("topic", jsonOf(two.copy(lectureCount = 4))) }
        assertThrows(IllegalArgumentException::class.java) { validate("topic", jsonOf(two.copy(lectureIds = listOf(1, 1)))) }
        assertThrows(IllegalArgumentException::class.java) { validateRelationships(listOf(WireRecord(exam.id, "exam", jsonOf(exam)), WireRecord(root.id, "topic", jsonOf(root)), WireRecord(one.id, "topic", jsonOf(one.copy(lectureIds = listOf(11)))))) }
    }
    @Test fun `cross subject moves release content without changing completion dates`() {
        val nodes = listOf(topic("a").copy(lectureCount = 10), topic("b").copy(lectureCount = 3), topic("leaf", "a", "2026-10-22").copy(lectureIds = listOf(7)))
        val moved = TopicIndex(nodes).moveChanges("leaf", "b").single().data.asModel<Topic>()
        assertTrue(moved.lectureIds.isEmpty()); assertEquals("2026-10-22", moved.completedOn)
    }
    @Test fun `study plans repeat by weekday and carry details without creating studied time`() {
        assertEquals(listOf("2026-10-26", "2026-10-28", "2026-10-30"), planDates("2026-10-26", "2026-11-01", setOf(1, 3, 5)))
        assertThrows(IllegalArgumentException::class.java) { planDates("2026-10-26", "2028-10-26") }
        assertThrows(IllegalArgumentException::class.java) { planDates("2026-10-26", weekdays = emptySet()) }
        val plan = StudyTask("plan", exam.id, "Practice calculus", "plan", "2026-10-26", minutes = 45, activity = "Practice", notes = "Solve 15 questions")
        validate("task", jsonOf(plan)); assertEquals(plan, jsonOf(plan).asModel<StudyTask>())
        assertThrows(IllegalArgumentException::class.java) { validate("task", jsonOf(plan.copy(minutes = null))) }
    }
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
