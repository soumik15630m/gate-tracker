package org.studyspace.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.math.roundToInt

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
val confidenceLabels = linkedMapOf("unrated" to "Not rated", "needs-work" to "Needs work", "getting-there" to "Getting there", "confident" to "Confident")
val activityLabels = listOf("Learning", "Revision", "Practice", "Mock test")
val mistakeLabels = listOf("Concept gap", "Recall", "Calculation", "Misreading", "Time pressure")
fun uid(prefix: String = "id") = "${prefix}_${UUID.randomUUID()}"
fun today(timezone: String = ZoneId.systemDefault().id): String = LocalDate.now(ZoneId.of(timezone)).toString()
fun addDays(day: String, count: Long) = LocalDate.parse(day).plusDays(count).toString()
fun weekStart(day: String) = LocalDate.parse(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
fun dayDifference(from: String, to: String) = LocalDate.parse(to).toEpochDay() - LocalDate.parse(from).toEpochDay()
fun displayDate(day: String) = runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault("No date")
fun duration(minutes: Int): String = if (minutes < 60) "${minutes}m" else "${minutes / 60}h" + if (minutes % 60 > 0) " ${minutes % 60}m" else ""
fun validDate(day: String) = day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && runCatching { LocalDate.parse(day).toString() == day }.getOrDefault(false)
inline fun <reified T> jsonOf(value: T): JsonObject = codec.encodeToJsonElement(value).jsonObject
inline fun <reified T> JsonObject.asModel(): T = codec.decodeFromJsonElement(this)

@Serializable data class Exam(val id: String, val name: String, val date: String, val weeklyTarget: Double = 20.0, val archived: Boolean = false, val description: String = "")
@Serializable data class Topic(val id: String, val examId: String, val parentId: String? = null, val title: String, val order: Double = 0.0, val completedOn: String? = null, val confidence: String = "unrated", val reviewOn: String? = null, val lastReviewedOn: String? = null, val focusOn: String? = null)
@Serializable data class StudySession(val id: String, val examId: String, val topicId: String? = null, val topicPath: String = "", val date: String, val minutes: Int, val activity: String = "Learning", val note: String = "", val mockId: String? = null)
@Serializable data class MockSection(val name: String, val score: Double, val maximum: Double)
@Serializable data class Mistake(val topicId: String? = null, val category: String = "Concept gap", val note: String = "")
@Serializable data class MockTest(val id: String, val examId: String, val name: String, val series: String = "", val date: String, val score: Double, val maximum: Double = 100.0, val minutes: Int = 180, val correct: Int? = null, val incorrect: Int? = null, val unattempted: Int? = null, val sections: List<MockSection> = emptyList(), val mistakes: List<Mistake> = emptyList())
@Serializable data class StudyTask(val id: String, val examId: String, val title: String, val type: String = "inbox", val date: String = "", val done: Boolean = false, val topicId: String? = null)
@Serializable data class Reflection(val id: String, val examId: String, val week: String, val worked: String = "", val improve: String = "", val next: String = "")
@Serializable data class Preferences(val id: String = "preferences", val timezone: String = ZoneId.systemDefault().id, val streakMinimum: Int = 1, val displayName: String = "")
@Serializable data class WireRecord(val id: String, val kind: String, val data: JsonObject, val version: Long = 0, val deleted: Boolean = false, val changedAt: String? = null)
@Serializable data class Change(val id: String, val kind: String, val data: JsonObject, val baseVersion: Long = 0, val mutationId: String = uid("mutation"), val deleted: Boolean = false)
@Serializable data class Conflict(val local: Change, val remote: WireRecord)
@Serializable data class Accepted(val id: String, val mutationId: String, val version: Long)
@Serializable data class SyncRequest(val cursor: Long, val changes: List<Change>)
@Serializable data class SyncResult(val cursor: Long, val records: List<WireRecord>, val accepted: List<Accepted>, val conflicts: List<Conflict>)
@Serializable data class BackupRecord(val kind: String, val data: JsonObject, val deleted: Boolean = false)
@Serializable data class Backup(val format: String = "studyspace", val version: Int = 1, val records: List<BackupRecord>)
data class Mutation(val kind: String, val data: JsonObject, val deleted: Boolean = false) { val id get() = data.getValue("id").jsonPrimitive.content }
inline fun <reified T> mutation(kind: String, value: T, deleted: Boolean = false) = Mutation(kind, jsonOf(value), deleted)

data class Progress(val total: Int = 0, val done: Int = 0, val closedOn: String? = null, val minutes: Int = 0) {
    val percent: Int get() = if (total == 0) 0 else (done * 100.0 / total).roundToInt()
}
data class VisibleTopic(val topic: Topic, val depth: Int, val progress: Progress, val hasChildren: Boolean)

/** An iterative index: one traversal for coverage and time, no recursive UI trees. */
class TopicIndex(val topics: List<Topic>, sessions: List<StudySession> = emptyList()) {
    val byId = topics.associateBy { it.id }
    val children = topics.groupBy { it.parentId }.mapValues { (_, items) -> items.sortedWith(compareBy<Topic> { it.order }.thenBy { it.title }) }
    val preorder: List<Topic>
    val progress: Map<String, Progress>
    val total: Progress
    init {
        val ordered = ArrayList<Topic>(topics.size)
        val stack = ArrayDeque<Topic>()
        children[null].orEmpty().asReversed().forEach(stack::addLast)
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!seen.add(node.id)) continue
            ordered.add(node)
            children[node.id].orEmpty().asReversed().forEach(stack::addLast)
        }
        preorder = ordered
        val directTime = HashMap<String, Int>()
        sessions.forEach { session -> session.topicId?.let { directTime[it] = (directTime[it] ?: 0) + session.minutes } }
        val aggregate = HashMap<String, Progress>(topics.size)
        for (node in ordered.asReversed()) {
            val childStats = children[node.id].orEmpty().mapNotNull { aggregate[it.id] }
            val count = if (childStats.isEmpty()) 1 else childStats.sumOf { it.total }
            val done = if (childStats.isEmpty()) if (node.completedOn != null) 1 else 0 else childStats.sumOf { it.done }
            val closed = if (done != count) null else if (childStats.isEmpty()) node.completedOn else childStats.mapNotNull { it.closedOn }.maxOrNull()
            aggregate[node.id] = Progress(count, done, closed, (directTime[node.id] ?: 0) + childStats.sumOf { it.minutes })
        }
        progress = aggregate
        val roots = children[null].orEmpty().mapNotNull { aggregate[it.id] }
        val leafCount = roots.sumOf { it.total }; val completed = roots.sumOf { it.done }
        total = Progress(leafCount, completed, if (leafCount > 0 && completed == leafCount) roots.mapNotNull { it.closedOn }.maxOrNull() else null, sessions.sumOf { it.minutes })
    }
    fun path(id: String?): String {
        val names = ArrayDeque<String>(); val seen = HashSet<String>(); var node = byId[id]
        while (node != null && seen.add(node.id)) { names.addFirst(node.title); node = byId[node.parentId] }
        return names.joinToString(" / ")
    }
    fun branch(id: String): Set<String> {
        val result = HashSet<String>(); val stack = ArrayDeque<String>(); stack.add(id)
        while (stack.isNotEmpty()) { val next = stack.removeLast(); if (result.add(next)) children[next].orEmpty().forEach { stack.add(it.id) } }
        return result
    }
    fun canMove(id: String, parent: String?) = byId[id] != null && (parent == null || byId[parent]?.examId == byId[id]?.examId && parent !in branch(id))
    fun visible(expanded: Set<String>, query: String = ""): List<VisibleTopic> {
        val included = if (query.isBlank()) null else HashSet<String>().also { found ->
            val term = query.trim().lowercase()
            for (node in preorder.asReversed()) if (node.title.lowercase().contains(term) || node.id in found) { found.add(node.id); node.parentId?.let(found::add) }
        }
        val result = ArrayList<VisibleTopic>()
        val stack = ArrayDeque<Pair<Topic, Int>>()
        children[null].orEmpty().asReversed().forEach { stack.add(it to 0) }
        while (stack.isNotEmpty()) {
            val (topic, depth) = stack.removeLast()
            if (included != null && topic.id !in included) continue
            val childNodes = children[topic.id].orEmpty()
            result.add(VisibleTopic(topic, depth, progress[topic.id] ?: Progress(), childNodes.isNotEmpty()))
            if (included != null || topic.id in expanded) childNodes.asReversed().forEach { stack.add(it to depth + 1) }
        }
        return result
    }
    fun completionChanges(id: String, day: String): List<Mutation> {
        val branchIds = branch(id)
        val completing = progress[id]?.closedOn == null
        val updated = topics.map { node -> if (node.id in branchIds) node.copy(completedOn = if (completing) node.completedOn ?: day else null) else node }
        val recalculated = TopicIndex(updated)
        return updated.mapNotNull { node ->
            val closed = if (children[node.id].isNullOrEmpty()) node.completedOn else recalculated.progress[node.id]?.closedOn
            val next = node.copy(completedOn = closed)
            if (next == byId[next.id]) null else mutation("topic", next)
        }
    }
}

data class Streak(val current: Int, val longest: Int, val activeDays: Int)
fun studyDays(sessions: List<StudySession>) = sessions.groupBy { it.date }.mapValues { (_, items) -> items.sumOf { it.minutes } }
fun streak(sessions: List<StudySession>, day: String, minimum: Int = 1): Streak {
    val active = studyDays(sessions).filter { (date, minutes) -> date <= day && minutes >= minimum }.keys.sorted()
    val set = active.toHashSet(); var cursor = if (day in set) day else addDays(day, -1); var current = 0
    while (cursor in set) { current++; cursor = addDays(cursor, -1) }
    var longest = 0; var run = 0; var previous: String? = null
    active.forEach { date -> run = if (previous != null && dayDifference(previous!!, date) == 1L) run + 1 else 1; longest = maxOf(longest, run); previous = date }
    return Streak(current, longest, active.size)
}
data class OutlineLine(val title: String, val depth: Int)
fun parseOutline(source: String): List<OutlineLine> {
    val lines = source.lines().filter { it.isNotBlank() }
    require(lines.size <= 3000) { "Import up to 3,000 topics at a time." }
    val indents = ArrayList<Int>()
    return lines.mapIndexed { index, raw ->
        val line = raw.replace("\t", "    "); val indent = line.takeWhile { it == ' ' }.length
        val title = line.trim().replace(Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+|\\[[ xX]\\]\\s*)"), "").trim()
        require(title.isNotBlank() && title.length <= 300) { "Line ${index + 1}: use a title of 1–300 characters." }
        if (indents.isEmpty() || indent > indents.last()) indents.add(indent)
        else { while (indents.size > 1 && indent < indents.last()) indents.removeAt(indents.lastIndex); require(indent == indents.last()) { "Line ${index + 1}: align with an earlier indentation level." } }
        OutlineLine(title, indents.lastIndex)
    }
}
fun outlineTopics(lines: List<OutlineLine>, exam: String, parent: String? = null, offset: Double = 0.0): List<Topic> {
    val parents = HashMap<Int, String>(); var order = offset
    return lines.mapIndexed { index, line ->
        val node = Topic(uid("topic"), exam, if (line.depth == 0) parent else parents[line.depth - 1], line.title, if (line.depth == 0) order++ else index.toDouble())
        parents[line.depth] = node.id; node
    }
}
fun csv(rows: List<List<Any?>>): String = rows.joinToString("\r\n") { row -> row.joinToString(",") { value ->
    var text = value?.toString().orEmpty(); if (text.firstOrNull() in listOf('=', '+', '@', '-')) text = "'$text"
    "\"${text.replace("\"", "\"\"")}\""
} }

fun validate(kind: String, data: JsonObject) {
    fun id(value: String?) = value != null && value.matches(Regex("[a-zA-Z0-9:_-]{1,180}"))
    fun text(value: String, max: Int = 300, required: Boolean = true) = value.length <= max && (!required || value.isNotBlank())
    fun date(value: String?) = value.isNullOrBlank() || validDate(value)
    require(id(data["id"]?.jsonPrimitive?.content)) { "Invalid record identifier." }
    if (kind != "exam" && kind != "preferences") require(id(data["examId"]?.jsonPrimitive?.content)) { "Choose an exam." }
    val valid = when (kind) {
        "exam" -> data.asModel<Exam>().let { text(it.name) && validDate(it.date) && it.weeklyTarget.isFinite() && it.weeklyTarget in 0.0..168.0 && text(it.description, 1000, false) }
        "topic" -> data.asModel<Topic>().let { text(it.title) && (it.parentId == null || id(it.parentId)) && it.order.isFinite() && kotlin.math.abs(it.order) <= 9007199254740991.0 && date(it.completedOn) && confidenceLabels.containsKey(it.confidence) && date(it.reviewOn) && date(it.focusOn) && date(it.lastReviewedOn) }
        "session" -> data.asModel<StudySession>().let { (it.topicId == null || id(it.topicId)) && text(it.topicPath, 10000, false) && validDate(it.date) && it.minutes in 1..1440 && it.activity in activityLabels && text(it.note, 10000, false) && (it.mockId == null || id(it.mockId)) }
        "mock" -> data.asModel<MockTest>().let { text(it.name) && text(it.series, 300, false) && validDate(it.date) && it.maximum.isFinite() && it.maximum in 0.01..1e6 && it.score.isFinite() && it.score in -it.maximum..it.maximum && it.minutes in 1..1440 && listOf(it.correct, it.incorrect, it.unattempted).all { n -> n == null || n in 0..1000000 } && it.sections.size <= 200 && it.sections.all { s -> text(s.name) && s.maximum.isFinite() && s.maximum in 0.01..1e6 && s.score.isFinite() && s.score in -s.maximum..s.maximum } && it.mistakes.size <= 300 && it.mistakes.all { m -> (m.topicId == null || id(m.topicId)) && m.category in mistakeLabels && text(m.note, 5000, false) } }
        "task" -> data.asModel<StudyTask>().let { text(it.title, 1000) && it.type in listOf("inbox", "milestone") && date(it.date) && (it.topicId == null || id(it.topicId)) }
        "reflection" -> data.asModel<Reflection>().let { validDate(it.week) && text(it.worked, 10000, false) && text(it.improve, 10000, false) && text(it.next, 10000, false) }
        "preferences" -> data.asModel<Preferences>().let { it.id == "preferences" && runCatching { ZoneId.of(it.timezone) }.isSuccess && it.streakMinimum in 1..1440 && text(it.displayName, 100, false) }
        else -> false
    }
    require(valid) { "Check the required fields, dates, and numeric values." }
}
fun validateRelationships(records: Collection<WireRecord>) {
    val live = records.filterNot { it.deleted }
    val exams = live.filter { it.kind == "exam" }.map { it.id }.toHashSet()
    val topics = live.filter { it.kind == "topic" }.associate { it.id to it.data.asModel<Topic>() }
    live.forEach { record -> if (record.kind !in listOf("exam", "preferences")) require(record.data["examId"]?.jsonPrimitive?.content in exams) { "A record refers to a missing exam." } }
    val done = HashSet<String>()
    for (topic in topics.values) {
        if (topic.id in done) continue
        val path = HashSet<String>(); var node: Topic? = topic
        while (node != null && node.id !in done) {
            require(path.add(node.id)) { "A topic cannot be moved inside its own branch." }
            val parent = node.parentId
            node = if (parent == null) null else topics[parent].also { require(it != null && it.examId == topic.examId) { "A topic has a missing parent or belongs to another exam." } }
        }
        done.addAll(path)
    }
}
fun readBackup(source: String): List<BackupRecord> {
    val backup = codec.decodeFromString<Backup>(source)
    require(backup.format == "studyspace" && backup.version == 1 && backup.records.size <= 20000) { "Choose a Studyspace version 1 backup, with up to 20,000 records." }
    val ids = HashSet<String>()
    val records = backup.records.map { record -> validate(record.kind, record.data); val id = record.data.getValue("id").jsonPrimitive.content; require(ids.add(id)) { "The backup contains duplicate records." }; WireRecord(id, record.kind, record.data, deleted = record.deleted) }
    validateRelationships(records)
    return backup.records
}

data class StudySnapshot(val records: List<WireRecord> = emptyList(), val pendingCount: Int = 0, val conflicts: List<Conflict> = emptyList(), val sample: Boolean = false) {
    private val live = records.filterNot { it.deleted }
    val exams = live.filter { it.kind == "exam" }.map { it.data.asModel<Exam>() }
    val topics = live.filter { it.kind == "topic" }.map { it.data.asModel<Topic>() }
    val sessions = live.filter { it.kind == "session" }.map { it.data.asModel<StudySession>() }
    val mocks = live.filter { it.kind == "mock" }.map { it.data.asModel<MockTest>() }
    val tasks = live.filter { it.kind == "task" }.map { it.data.asModel<StudyTask>() }
    val reflections = live.filter { it.kind == "reflection" }.map { it.data.asModel<Reflection>() }
    val preferences = live.firstOrNull { it.kind == "preferences" }?.data?.asModel<Preferences>() ?: Preferences()
    val today = today(preferences.timezone)
    val streak = streak(sessions, today, preferences.streakMinimum)
}
