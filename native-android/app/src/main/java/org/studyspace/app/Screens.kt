package org.studyspace.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.ceil

private val pagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp)
@Composable fun TodayScreen(w: Workspace, vm: NativeViewModel) {
    val due = remember(w) { w.topics.filter { !it.reviewOn.isNullOrBlank() && it.reviewOn <= w.day }.sortedBy { it.reviewOn } }
    val focus = remember(w) { w.topics.filter { it.focusOn == w.day } }
    val weeklyValues = remember(w) { (0L..6L).map { w.days[addDays(weekStart(w.day), it)] ?: 0 } }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "heading") { PageHeading("One day at a time", "Make today count.", if (w.state.preferences.displayName.isBlank()) "A quiet space for meaningful progress." else "Welcome back, ${w.state.preferences.displayName}.") }
        if (w.activeExams.isEmpty()) {
            item { EmptyCard("Your next chapter starts here", "Add an exam, bring in your syllabus, and take your first small step.") { Action("Create your first exam", icon = Icons.Outlined.Add) { vm.open(Editor.ExamForm()) } } }
            return@LazyColumn
        }
        w.nearest?.let { exam -> item(key = "countdown") {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Label("THE NEXT CHAPTER", Palette.sage); Text(exam.name, fontFamily = Sans, color = Palette.text, fontSize = 16.sp); Body(displayDate(exam.date)) }
                    Column(horizontalAlignment = Alignment.End) { Text(dayDifference(w.day, exam.date).coerceAtLeast(0).toString(), fontFamily = Serif, fontSize = 48.sp, color = Palette.sage); Label("DAYS TO GO") }
                }
                Meter(w.index.total.percent)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("${w.index.total.percent}% of the syllabus covered"); Body("${w.index.total.done}/${w.index.total.total}", color = Palette.teal) }
                Action("Log study time", Modifier.fillMaxWidth(), icon = Icons.Outlined.Add) { vm.quickLog() }
                Action("See your plan", Modifier.fillMaxWidth(), secondary = true) { vm.select(exam.id); vm.screen.value = Screen.PLAN }
            }
        } }
        item(key = "stats-a") { StatPair({ Stat("STUDIED TODAY", duration(w.days[w.day] ?: 0), "${w.sessions.count { it.date == w.day }} study entries", it) }, { Stat("CURRENT STREAK", "${w.state.streak.current} days", "Best: ${w.state.streak.longest} days", it, Palette.gold) }) }
        item(key = "stats-b") { StatPair({ Stat("THIS WEEK", duration(w.weekMinutes), "of ${w.target.toInt()}h weekly intention", it) }, { Stat("COVERAGE", "${w.index.total.percent}%", "${w.index.total.done} of ${w.index.total.total} final items", it, Palette.teal) }) }
        item(key = "weekly") { Card(title = "A little more, every day", subtitle = "Your study time this week") { TimeBars(weeklyValues, listOf("M", "T", "W", "T", "F", "S", "S"), dates = (0L..6L).map { addDays(weekStart(w.day), it) }, sessions = w.sessions); Meter(if (w.target > 0) (w.weekMinutes / (w.target * 60) * 100).toInt() else 0, color = Palette.sage) } }
        if (focus.isNotEmpty()) item(key = "focus-heading") { SectionTitle("Today’s focus", "${focus.size} selected topics") }
        items(focus, key = { "focus-${it.id}" }, contentType = { "focus" }) { topic -> TopicSummary(topic, w.index, vm) }
        item(key = "due") { Card(title = "Make room to remember", subtitle = "${due.size} topics are ready for revision") { Action("Quick add revision", secondary = true, icon = Icons.Outlined.Add) { vm.open(Editor.QuickRevision) }; if (due.isNotEmpty()) Body(due.take(2).joinToString(" / ") { it.title }) else Body("Schedule a return to a subject, chapter or subtopic."); Action("Open revision queue", secondary = true, icon = Icons.Outlined.History) { vm.screen.value = Screen.REVISION } } }
        item(key = "heatmap") { Card(title = "Consistency, made visible", subtitle = "Every small session leaves a mark.") { ActivityGrid(w.days, w.day, sessions = w.sessions); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("Last 13 weeks"); Body("Less  ▪ ▪ ▪  More", color = Palette.teal) } } }
        item(key = "inbox-title") { SectionTitle("Clear your mind", "Quick capture") }
        item(key = "inbox-add") { Action("Add an inbox note", secondary = true, icon = Icons.Outlined.Add) { vm.open(Editor.TaskForm(type = "inbox")) } }
        items(w.tasks.filter { it.type == "inbox" && !it.done }, key = { "inbox-${it.id}" }, contentType = { "task" }) { task -> TaskRow(task, vm) }
    }
}
@Composable fun SectionTitle(title: String, subtitle: String = "") { Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.text); if (subtitle.isNotEmpty()) Body(subtitle) } }
@Composable fun TopicSummary(topic: Topic, index: TopicIndex, vm: NativeViewModel) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressCheck(index.progress[topic.id] ?: Progress(), topic.title) { vm.toggleTopic(topic.id) }
            Column(Modifier.weight(1f).clickable { vm.open(Editor.TopicDetail(topic.id)) }, verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(topic.title, fontFamily = Sans, color = Palette.text, fontSize = 14.sp); index.byId[topic.parentId]?.let { Body(it.title, maxLines = 1) } }
            IconAction("Log ${topic.title}", Icons.Outlined.AddCircleOutline, Palette.sage) { vm.open(Editor.LogForm(topic = topic.id)) }
        }
    }
}

@Composable fun SyllabusScreen(w: Workspace, vm: NativeViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable(w.selected) { mutableStateOf(w.index.children[null].orEmpty().map { it.id }.toSet()) }
    var rows by remember { mutableStateOf<List<VisibleTopic>>(emptyList()) }
    LaunchedEffect(w.index, expanded, query) { if (query.isNotBlank()) delay(130); rows = withContext(Dispatchers.Default) { if (query.isBlank()) w.index.visible(expanded) else {
        val included = w.index.smartSearch.results(query, limit = 20000).map { it.topic.id }.toHashSet()
        w.index.preorder.asReversed().forEach { node -> if (node.id in included) node.parentId?.let(included::add) }
        w.index.visible(w.index.byId.keys).filter { it.topic.id in included }
    } } }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { contentDescription = "Syllabus list"; stateDescription = "${rows.size} topics" }) {
        item(key = "title", contentType = "heading") { PageHeading("The whole picture", "One topic at a time.", "Check off what you finish. Confidence stays independent.") }
        item(key = "actions", contentType = "actions") { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Action("Add topic", Modifier.weight(1f), icon = Icons.Outlined.Add, enabled = w.examId != null) { vm.open(Editor.TopicForm()) }; Action("Import outline", Modifier.weight(1f), secondary = true, icon = Icons.Outlined.PlaylistAdd, enabled = w.examId != null) { vm.open(Editor.ImportOutline()) } } }
        item(key = "search", contentType = "search") { Field("Search syllabus", query, { query = it }, hint = "Find a topic at any depth") }
        item(key = "coverage", contentType = "coverage") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("${w.index.total.done} / ${w.index.total.total} final items"); Body("${w.index.total.percent}%", color = Palette.teal) }; Spacer(Modifier.height(8.dp)); Meter(w.index.total.percent) }
        if (rows.isEmpty()) item { EmptyCard(if (query.isBlank()) "Your syllabus starts here" else "No matching topics", if (query.isBlank()) "Add a subject or paste an indented outline to build the whole tree." else "Try a different name. Search includes every depth.") }
        items(rows, key = { it.topic.id }, contentType = { "topic" }) { row ->
            val topic = row.topic; val progress = row.progress
            Row(Modifier.fillMaxWidth().background(if (row.depth == 0) Palette.panel else Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(start = minOf(row.depth * 9, 36).dp, end = 2.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.hasChildren) IconAction(if (topic.id in expanded) "Collapse ${topic.title}" else "Expand ${topic.title}", if (topic.id in expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight) { expanded = if (topic.id in expanded) expanded - topic.id else expanded + topic.id }
                else Spacer(Modifier.width(16.dp))
                ProgressCheck(progress, topic.title) { vm.toggleTopic(topic.id) }
                Column(Modifier.weight(1f).clickable { vm.open(Editor.TopicDetail(topic.id)) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(topic.title, fontFamily = Sans, color = if (progress.closedOn != null) Palette.muted else Palette.text, fontSize = 14.sp, lineHeight = 20.sp, textDecoration = if (progress.closedOn != null) TextDecoration.LineThrough else null)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (row.depth > 4) Text("LEVEL ${row.depth + 1}", fontFamily = Sans, fontSize = 9.sp, color = Palette.sage)
                        if (progress.closedOn != null) Body("Closed ${displayDate(progress.closedOn)}", maxLines = 1)
                        else if (row.hasChildren) Body("${progress.done}/${progress.total} complete", maxLines = 1)
                        if (progress.minutes > 0) Body(duration(progress.minutes), maxLines = 1)
                    }
                    ImportanceBadge(topic.importance)
                    val confidenceColor = when (topic.confidence) { "confident" -> Palette.teal; "needs-work" -> Palette.gold; else -> Palette.muted }
                    Text(confidenceLabels[topic.confidence].orEmpty(), color = confidenceColor, fontFamily = Sans, fontSize = 10.sp, modifier = Modifier.clickable { vm.open(Editor.TopicDetail(topic.id)) }.padding(vertical = 3.dp))
                }
                IconAction("Actions for ${topic.title}", Icons.Outlined.MoreVert) { vm.open(Editor.TopicDetail(topic.id)) }
            }
        }
    }
}

@Composable fun LogScreen(w: Workspace, vm: NativeViewModel) {
    val ordered = remember(w.sessions) { w.sessions.sortedWith(compareByDescending<StudySession> { it.date }.thenByDescending { it.id }) }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("Your effort, remembered", "Time well spent.", "Log the time you studied, against any syllabus level.") }
        item { Action("Log study time", Modifier.fillMaxWidth(), icon = Icons.Outlined.Add, enabled = w.examId != null) { vm.quickLog() } }
        item { StatPair({ Stat("TOTAL EFFORT", duration(w.sessions.sumOf { it.minutes }), "${w.sessions.size} study entries", it) }, { Stat("STUDY DAYS", w.days.filter { it.key <= w.day }.size.toString(), "Every day adds up", it, Palette.teal) }) }
        if (ordered.isEmpty()) item { EmptyCard("Make your first entry", "No timer to manage. Just the time you studied and the topic you worked on.") }
        items(ordered, key = { it.id }, contentType = { "session" }) { session ->
            Card(Modifier.clickable { vm.open(Editor.LogForm(session)) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Chip(session.activity, selected = true, color = if (session.activity == "Revision") Palette.teal else Palette.sage); Text(duration(session.minutes), fontFamily = Serif, color = Palette.text, fontSize = 25.sp) }
                Text(session.topicPath.ifBlank { w.state.exams.firstOrNull { it.id == session.examId }?.name ?: "General study" }, fontFamily = Sans, color = Palette.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (session.note.isNotBlank()) Body(session.note, maxLines = 3)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body(displayDate(session.date)); Body("Edit entry", color = Palette.sage) }
            }
        }
    }
}

@Composable fun RevisionScreen(w: Workspace, vm: NativeViewModel) {
    var importance by rememberSaveable { mutableStateOf("all") }
    var level by rememberSaveable { mutableStateOf("all") }
    var source by rememberSaveable { mutableStateOf("self") }
    var query by rememberSaveable { mutableStateOf("") }
    var upcoming by rememberSaveable { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<Topic>>(emptyList()) }
    LaunchedEffect(w, upcoming, importance, level, source, query) { delay(90); queue = withContext(Dispatchers.Default) { w.index.smartSearch.results(query, TopicFilter(importance, level, source), 20000).map { it.topic }.filter { !it.reviewOn.isNullOrBlank() && (upcoming || it.reviewOn <= w.day) }.sortedBy { it.reviewOn } } }

    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("Remember, with intention", "Return a little stronger.", "A calm queue for topics worth revisiting.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Chip("Due now", !upcoming) { upcoming = false }; Chip("All scheduled", upcoming) { upcoming = true } } }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { TopicFilterControls(TopicFilter(importance, level, source)) { importance = it.importance; level = it.level; source = it.source }; Field("Search revision topics", query, { query = it }, hint = "Any topic or parent path") } }
        if (queue.isEmpty()) item { EmptyCard("A little breathing room", if (importance == "all") "Schedule revision from any topic’s details, or mark a mock-test weak area for review." else "No items with this importance rating in this queue. Change the filter or rate an item in Syllabus.") }
        items(queue, key = { it.id }, contentType = { "revision" }) { topic ->
            Card(title = topic.title, subtitle = w.index.byId[topic.parentId]?.title) {
                ImportanceBadge(topic.importance)
                Action("Edit importance", secondary = true) { vm.open(Editor.TopicDetail(topic.id)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Chip(if (topic.reviewOn!! < w.day) "Overdue" else if (topic.reviewOn == w.day) "Today" else displayDate(topic.reviewOn), selected = true, color = if (topic.reviewOn < w.day) Palette.gold else Palette.teal); Body(confidenceLabels[topic.confidence].orEmpty()) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Action("Review & reschedule", Modifier.weight(1f)) { vm.open(Editor.Review(topic.id)) }; IconAction("Log revision time", Icons.Outlined.AddCircleOutline, Palette.sage) { vm.open(Editor.LogForm(topic = topic.id)) } }
            }
        }
    }
}

@Composable fun MocksScreen(w: Workspace, vm: NativeViewModel) {
    val series = remember(w.mocks) { w.mocks.map { it.series.ifBlank { "Unlabelled series" } }.distinct() }
    var selectedSeries by rememberSaveable { mutableStateOf("") }
    val selected = if (selectedSeries in series) selectedSeries else series.firstOrNull().orEmpty()
    val trend = remember(w.mocks, selected) { w.mocks.filter { it.series.ifBlank { "Unlabelled series" } == selected } }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("Practice with perspective", "Progress beyond a score.", "Record results, understand mistakes, and come back stronger.") }
        item { Action("Add mock result", Modifier.fillMaxWidth(), icon = Icons.Outlined.Add, enabled = w.examId != null) { vm.open(Editor.MockForm()) } }
        if (w.selected == "all" && w.activeExams.size > 1) item { Body("Choose an exam above to compare results from the same preparation workspace.") }
        else if (trend.isNotEmpty()) item { Card(title = "Your score trend", subtitle = "Percent of maximum · comparable test series") { Choice("Series", selected, series.map { it to it }) { selectedSeries = it }; TrendLine(trend.map { it.score / it.maximum * 100 }); Body("${trend.size} results in this series") } }
        if (w.mocks.isEmpty()) item { EmptyCard("Your first checkpoint", "Add a result with section scores and mistakes. Linking its study time is optional.") }
        items(w.mocks.asReversed(), key = { it.id }, contentType = { "mock" }) { mock ->
            Card(Modifier.clickable { vm.open(Editor.MockForm(mock)) }, title = mock.name, subtitle = "${mock.series.ifBlank { "Unlabelled series" }} · ${displayDate(mock.date)}") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${formatScore(mock.score)} / ${formatScore(mock.maximum)}", fontFamily = Serif, fontSize = 30.sp, color = Palette.teal); Body(duration(mock.minutes)) }
                Meter((mock.score / mock.maximum * 100).toInt())
                mock.sections.take(4).forEach { section -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body(section.name); Body("${formatScore(section.score)} / ${formatScore(section.maximum)}", color = Palette.text) } }
                if (mock.mistakes.isNotEmpty()) Body("${mock.mistakes.size} mistakes recorded · Tap to review", color = Palette.gold)
            }
        }
        val mistakes = w.mocks.flatMap { mock -> mock.mistakes.mapIndexed { i, mistake -> Triple("${mock.id}:$i", mock, mistake) } }
        if (mistakes.isNotEmpty()) item { SectionTitle("Your error journal", "Patterns to work on, without judgement.") }
        items(mistakes, key = { it.first }, contentType = { "mistake" }) { (_, mock, mistake) -> Card(title = mistake.category, subtitle = mock.name) { Body(mistake.note.ifBlank { "No note added" }); mistake.topicId?.let { id -> w.index.byId[id]?.let { topic -> Body(topic.title, color = Palette.sage); Action("Schedule revision", secondary = true) { vm.open(Editor.Review(id)) } } } } }
    }
}
fun formatScore(score: Double) = if (score % 1.0 == 0.0) score.toInt().toString() else "%.1f".format(score)

@Composable fun InsightsScreen(w: Workspace, vm: NativeViewModel) {
    var period by rememberSaveable { mutableIntStateOf(30) }
    val metrics by produceState<InsightMetrics?>(null, w, period) { value = withContext(Dispatchers.Default) { InsightMetrics(w, period) } }
    val data = metrics
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("The shape of your effort", "Look how far you’ve come.", "Real signals from the work you put in.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf(7, 30, 90).forEach { count -> Chip("${count} days", count == period) { period = count } } } }
        if (data == null) item { Body("Preparing your insights…") }
        else {
            item { StatPair({ Stat("STUDY TIME", duration(data.minutes), "${data.sessions} entries in this period", it) }, { Stat("ACTIVE DAYS", data.active.toString(), "${duration(data.average)} average per study day", it, Palette.teal) }) }
            item { StatPair({ Stat("CHECKLIST CLOSED", data.closed.toString(), "Closing dates within this period", it, Palette.teal) }, { Stat("LONGEST STREAK", "${w.state.streak.longest} days", "Across all your exams", it, Palette.gold) }) }
            item { Card(title = "Your effort over time", subtitle = "Daily study minutes") { TimeBars(data.bars, data.labels, dates = data.barDates, sessions = w.sessions, bucketDays = data.bucketWidth) } }
            item { Card(title = "Consistency", subtitle = "Thirteen weeks of showing up") { ActivityGrid(w.days, w.day, sessions = w.sessions) } }
            item { Card(title = "Where your time goes", subtitle = "Direct and nested study time, counted once") {
                data.subjects.take(8).forEach { (name, minutes) -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body(name, Modifier.weight(1f), Palette.text, 1); Body(duration(minutes)) }; Meter(if (data.allTime > 0) minutes * 100 / data.allTime else 0) } }
                if (data.subjects.size > 8) Body("Your eight leading subjects by study time. Coverage for every subject is listed below.")
                if (data.general > 0) Body("General study: ${duration(data.general)}")
            } }
            item { Card(title = "Confidence, honestly", subtitle = "Final checklist items · independent of completion") { data.confidence.forEach { (key, count) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body(confidenceLabels[key].orEmpty(), color = if (key == "confident") Palette.teal else Palette.muted); Body(count.toString(), color = Palette.text) } } } }
            item { SectionTitle("Subject coverage", "Closing one final item moves the whole branch forward.") }
            items(w.index.children[null].orEmpty(), key = { "coverage-${it.id}" }, contentType = { "subject" }) { topic -> val progress = w.index.progress[topic.id] ?: Progress(); Card(title = topic.title) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("${progress.done}/${progress.total} complete"); Body("${progress.percent}%", color = Palette.teal) }; Meter(progress.percent); ContentMeters(w.index, topic.id) } }
            if (data.neglected.isNotEmpty()) item { Card(title = "A gentle nudge", subtitle = "Subjects with no logged study time in the last 14 days") { data.neglected.take(5).forEach { Body(it, color = Palette.gold) } } }
            if (data.forecast != null) item { Card(title = "At your recent pace") { Body("Around ${displayDate(data.forecast)} to finish the remaining checklist items.", color = Palette.text); Body("An approximate forecast from the last 28 days of closing dates. It estimates coverage, not mastery.") } }
            item { Body("Completion metrics use current closing dates. Reopening an item removes its closing date; no invented event history is used.") }
        }
    }
}
class InsightMetrics(w: Workspace, period: Int) {
    private val start = addDays(w.day, -(period - 1).toLong())
    private val entries = w.sessions.filter { it.date >= start && it.date <= w.day }
    val minutes = entries.sumOf { it.minutes }
    val sessions = entries.size
    val active = entries.map { it.date }.distinct().size
    val average = if (active == 0) 0 else minutes / active
    private val leaves = w.topics.filter { w.index.children[it.id].isNullOrEmpty() }
    val closed = leaves.count { it.completedOn != null && it.completedOn >= start && it.completedOn <= w.day }
    private val buckets = if (period <= 7) 7 else 10
    private val width = ceil(period / buckets.toDouble()).toInt()
    val bucketWidth = width
    val barDates = (0 until buckets).map { addDays(start, (it * width).toLong()) }
    val bars = (0 until buckets).map { n -> val first = addDays(start, (n * width).toLong()); val last = addDays(first, (width - 1).toLong()); entries.filter { it.date in first..last }.sumOf { it.minutes } }
    val labels = (0 until buckets).map { n -> if (n == 0 || n == buckets - 1 || buckets == 7) addDays(start, (n * width).toLong()).takeLast(2) else "" }
    val allTime = w.sessions.sumOf { it.minutes }
    val subjects = w.index.children[null].orEmpty().map { it.title to (w.index.progress[it.id]?.minutes ?: 0) }.sortedByDescending { it.second }
    val general = w.sessions.filter { it.topicId == null || it.topicId !in w.index.byId }.sumOf { it.minutes }
    val confidence = confidenceLabels.keys.map { key -> key to leaves.count { it.confidence == key } }
    val neglected = run {
        val recent = TopicIndex(w.topics, w.sessions.filter { it.date >= addDays(w.day, -13) && it.date <= w.day })
        recent.children[null].orEmpty().filter { (recent.progress[it.id]?.minutes ?: 0) == 0 }.map { it.title }
    }
    private val recentlyClosed = leaves.count { it.completedOn != null && it.completedOn >= addDays(w.day, -27) && it.completedOn <= w.day }
    val forecast: String? = if (recentlyClosed > 0 && w.index.total.done < w.index.total.total) addDays(w.day, ceil((w.index.total.total - w.index.total.done) * 28.0 / recentlyClosed).toLong()) else null
}

@Composable fun PlanScreen(w: Workspace, vm: NativeViewModel) {
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("A direction, not a deadline", "Make space for what’s next.", "Your exams, weekly intentions, and moments to reflect.") }
        item { Action("Add an exam", icon = Icons.Outlined.Add) { vm.open(Editor.ExamForm()) } }
        items(w.state.exams.sortedBy { it.date }, key = { "exam-${it.id}" }, contentType = { "exam" }) { exam -> Card(Modifier.clickable { vm.open(Editor.ExamForm(exam)) }, title = exam.name, subtitle = displayDate(exam.date)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${dayDifference(w.day, exam.date).coerceAtLeast(0)} days", fontFamily = Serif, fontSize = 28.sp, color = Palette.sage); Body(if (exam.archived) "Archived" else "${formatScore(exam.weeklyTarget)}h / week") }; if (exam.description.orEmpty().isNotBlank()) Body(exam.description.orEmpty()); Action("Open workspace", secondary = true) { vm.select(exam.id); vm.screen.value = Screen.TODAY } } }
        item { SectionTitle("Your detailed study plan", "Dated blocks, clear goals and time to work.") }
        item { Action("Create detailed plan", icon = Icons.Outlined.Add, enabled = w.examId != null) { vm.open(Editor.TaskForm(type = "plan")) } }
        item {
            val blocks = w.tasks.filter { it.type == "plan" }; val planned = blocks.sumOf { it.minutes ?: 0 }; val done = blocks.filter { it.done }.sumOf { it.minutes ?: 0 }
            if (blocks.isNotEmpty()) Card { Body("${blocks.count { it.done }} / ${blocks.size} blocks complete / ${duration(done)} of ${duration(planned)} planned"); Meter(if (planned == 0) 0 else done * 100 / planned) }
        }
        items(w.tasks.filter { it.type == "plan" }.sortedBy { it.date }, key = { "plan-${it.id}" }, contentType = { "study-block" }) { block ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(block.done, { checked -> vm.save(listOf(mutation("task", block.copy(done = checked))), "Study plan updated", false) }); Body(block.title, Modifier.weight(1f).clickable { vm.open(Editor.TaskForm(block, "plan")) }, Palette.text) }
                Body("${displayDate(block.date)} / ${duration(block.minutes ?: 0)} / ${block.activity}")
                block.topicId?.let { Body(w.index.path(it).ifBlank { "Topic removed" }) }
                if (!block.notes.isNullOrBlank()) Body(block.notes)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Action("Edit block", Modifier.weight(1f), secondary = true) { vm.open(Editor.TaskForm(block, "plan")) }; Action("Log actual time", Modifier.weight(1f)) { vm.open(Editor.LogForm(topic = block.topicId)) } }
            }
        }
        item { SectionTitle("Milestones", "Small checkpoints on a longer journey.") }
        item { Action("Add milestone", secondary = true, icon = Icons.Outlined.Add, enabled = w.examId != null) { vm.open(Editor.TaskForm()) } }
        items(w.tasks.filter { it.type == "milestone" }.sortedBy { it.date }, key = { "milestone-${it.id}" }, contentType = { "task" }) { TaskRow(it, vm) }
        item { Card(title = "Your weekly reflection", subtitle = "Week of ${displayDate(weekStart(w.day))}") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("Effort this week"); Body(duration(w.weekMinutes), color = Palette.teal) }; val closed = w.topics.count { w.index.children[it.id].isNullOrEmpty() && it.completedOn != null && it.completedOn >= weekStart(w.day) && it.completedOn <= w.day }; Body("$closed final checklist items closed"); Action("Reflect on your week", secondary = true, enabled = w.examId != null) { vm.open(Editor.ReflectionForm(w.reflections.firstOrNull { it.week == weekStart(w.day) && it.examId == w.examId })) } } }
        items(w.reflections.sortedByDescending { it.week }, key = { "reflection-${it.id}" }, contentType = { "reflection" }) { reflection -> Card(Modifier.clickable { vm.open(Editor.ReflectionForm(reflection)) }, title = "Week of ${displayDate(reflection.week)}") { if (reflection.worked.isNotBlank()) { Label("WHAT WORKED", Palette.teal); Body(reflection.worked, color = Palette.text) }; if (reflection.improve.isNotBlank()) { Label("ROOM TO GROW"); Body(reflection.improve) }; if (reflection.next.isNotBlank()) { Label("NEXT WEEK’S FOCUS", Palette.sage); Body(reflection.next, color = Palette.text) } } }
    }
}
@Composable fun TaskRow(task: StudyTask, vm: NativeViewModel) { Card { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(task.done, { checked -> vm.save(listOf(mutation("task", task.copy(done = checked))), "Milestone updated", false) }); Column(Modifier.weight(1f).clickable { vm.open(Editor.TaskForm(task, task.type)) }) { Text(task.title, color = if (task.done) Palette.muted else Palette.text, fontFamily = Sans, fontSize = 14.sp, textDecoration = if (task.done) TextDecoration.LineThrough else null); if (task.date.isNotEmpty()) Body(displayDate(task.date)) } } } }

@Composable fun SettingsScreen(w: Workspace, vm: NativeViewModel) {
    val context = LocalContext.current
    val account by vm.repo.account.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val migration by vm.repo.migration.collectAsStateWithLifecycle()
    val updates by vm.updater.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var exportType by remember { mutableStateOf("backup") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.perform(false) { val source = vm.repo.backup(); withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(source.toByteArray()) } ?: error("Could not write this document.") }; vm.repo.messages.emit("Backup exported") } }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) vm.perform(false) {
        val source = withContext(Dispatchers.Default) { if (exportType == "sessions") csv(listOf(listOf("Date", "Exam", "Topic", "Minutes", "Activity", "Note")) + w.state.sessions.map { listOf(it.date, w.state.exams.firstOrNull { exam -> exam.id == it.examId }?.name, it.topicPath, it.minutes, it.activity, it.note) }) else csv(listOf(listOf("Date", "Exam", "Name", "Series", "Score", "Maximum", "Minutes")) + w.state.mocks.map { listOf(it.date, w.state.exams.firstOrNull { exam -> exam.id == it.examId }?.name, it.name, it.series, it.score, it.maximum, it.minutes) }) }
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(source.toByteArray()) } ?: error("Could not write this document.") }; vm.repo.messages.emit("CSV exported")
    } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.perform(false) {
        val source = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384); while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 16 * 1024 * 1024) { "Choose a backup smaller than 16 MB." }; output.write(buffer, 0, count) }; output.toString("UTF-8") } ?: error("Could not open this document.") }
        val count = withContext(Dispatchers.Default) { readBackup(source).size }
        vm.editor.value = Editor.RestoreBackup(source, count)
    } }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("Make it yours", "A workspace that stays.", "Your preferences, your data, your devices.") }
        item { PreferencesCard(w.state.preferences, vm) }
        item { Card(title = "Account & sync", subtitle = "The same server and account connect Android and desktop web.") {
            if (account == null) { Body("Changes are saved offline on this device."); Action("Sign in or create account", Modifier.fillMaxWidth(), enabled = !busy) { vm.open(Editor.SignIn) } }
            else { Body(account!!.user.email, color = Palette.text); Body(account!!.server); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Chip(status, selected = true); Body("${w.state.pendingCount} queued changes") }; Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Action("Sync now", Modifier.weight(1f), enabled = !busy) { vm.perform(false) { vm.repo.sync() } }; Action("Sign out", Modifier.weight(1f), secondary = true, enabled = !busy) { vm.perform(false) { vm.repo.signOut() } } } }
        } }
        if (w.state.conflicts.isNotEmpty()) item { Card(title = "Review concurrent changes", subtitle = "${w.state.conflicts.size} records changed on both devices. The full batch is preserved.") {
            w.state.conflicts.take(4).forEach { conflict -> Body("${conflict.local.kind}: ${conflict.local.data["title"]?.jsonPrimitive?.content ?: conflict.local.data["name"]?.jsonPrimitive?.content ?: conflict.local.id}", color = Palette.text) }
            Body("Export a backup first if you want to keep both versions. Resolve the batch together to keep syllabus branches coherent.")
            Action("Keep this device’s changes", Modifier.fillMaxWidth(), enabled = !busy) { vm.perform(false) { vm.repo.resolveConflicts(true) } }
            Action("Keep server versions", Modifier.fillMaxWidth(), secondary = true, enabled = !busy) { vm.perform(false) { vm.repo.resolveConflicts(false) } }
        } }
        item { Card(title = "Export & recovery", subtitle = "A full backup includes your exam workspaces, not your login credentials.") {
            Action("Export JSON backup", Modifier.fillMaxWidth(), secondary = true, enabled = !busy) { export.launch("studyspace-${w.day}.json") }
            Action("Restore a JSON backup", Modifier.fillMaxWidth(), secondary = true, enabled = !busy) { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Action("Study CSV", Modifier.weight(1f), secondary = true) { exportType = "sessions"; csvExport.launch("study-log-${w.day}.csv") }; Action("Mock CSV", Modifier.weight(1f), secondary = true) { exportType = "mocks"; csvExport.launch("mock-results-${w.day}.csv") } }
            Action("Recover previous restore point", Modifier.fillMaxWidth(), secondary = true, enabled = !busy) { vm.perform(false) { vm.repo.recover() } }
        } }
        if (migration.isNotEmpty()) item { Card(title = "Previous app data") { Body(migration) } }
        item { Card(title = "Native app updates", subtitle = "Kotlin + Jetpack Compose · ${BuildConfig.VERSION_NAME}") {
            Body(updates.message)
            updates.release?.let { if (it.notes.isNotBlank()) Body(it.notes.take(1200)) }
            updates.progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth(), color = Palette.sage); Body("$it% downloaded") }
            if (updates.verifiedApk != null) Action("Install verified update", Modifier.fillMaxWidth()) { vm.updater.install() }
            else if (updates.release != null && updates.progress == null) Action("Download signed update", Modifier.fillMaxWidth()) { vm.perform(false) { vm.updater.download() } }
            Action(if (updates.checking) "Checking…" else "Check GitHub releases", Modifier.fillMaxWidth(), secondary = true, enabled = !updates.checking && updates.progress == null) { vm.perform(false) { vm.updater.check() } }
            Body("APK checksum and signing certificate are verified before Android asks you to approve installation.")
        } }
        item { Card(title = "Start fresh") { Body("Clear this workspace after saving a recovery point. Account workspaces sync this change to your other devices."); Action("Start a fresh workspace", secondary = true, enabled = !busy) { vm.open(Editor.Fresh) } } }
        item { Body("Open source · Apache 2.0\ngithub.com/soumik15630m/gate-tracker\nBuilt for steady, deliberate progress.") }
    }
}
@Composable private fun PreferencesCard(preferences: Preferences, vm: NativeViewModel) {
    var name by rememberSaveable(preferences) { mutableStateOf(preferences.displayName) }
    var timezone by rememberSaveable(preferences) { mutableStateOf(preferences.timezone) }
    var minimum by rememberSaveable(preferences) { mutableStateOf(preferences.streakMinimum.toString()) }
    Card(title = "Your rhythm") {
        Field("Display name", name, { name = it })
        Field("Timezone", timezone, { timezone = it }, hint = "Asia/Kolkata")
        Field("Minutes for a streak day", minimum, { minimum = it }, numeric = true)
        Action("Save preferences", secondary = true) { vm.perform(false) { val min = minimum.toIntOrNull() ?: error("Enter a whole number of minutes."); vm.repo.commit(listOf(mutation("preferences", Preferences(timezone = timezone.trim(), streakMinimum = min, displayName = name.trim()))), "Preferences saved") } }
    }
}
