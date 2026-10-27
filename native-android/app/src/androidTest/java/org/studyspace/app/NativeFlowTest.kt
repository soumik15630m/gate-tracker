package org.studyspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on a dedicated test device: the fixture replaces its local workspace. */
@RunWith(AndroidJUnit4::class)
class NativeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NativeViewModel::class.java]
    private fun settled(predicate: () -> Boolean) = compose.waitUntil(20000, predicate)

    @Test fun stopwatchRecoveryIsIsolatedByWorkspaceAndStaleScopeCannotWrite() {
        settled { vm.repo.ready.value }
        val application = compose.activity.application
        val prefs = application.getSharedPreferences("study_stopwatch_v1", 0)
        prefs.edit().remove("timer-test-A").remove("timer-test-B").commit()
        val scopes = MutableStateFlow("timer-test-A")
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: NativeStopwatch
        try {
            compose.runOnIdle { controller = NativeStopwatch(application, scopes, worker); controller.start(); controller.toggle() }
            val first = controller.timer.value!!
            assertNull(first.startedAt)
            compose.runOnIdle { controller = NativeStopwatch(application, scopes, worker) }
            assertEquals(first, controller.timer.value)
            compose.runOnIdle { scopes.value = "timer-test-B" }
            settled { controller.timer.value == null }
            compose.runOnIdle { controller.start() }
            val second = controller.timer.value!!.id
            assertNotEquals(first.id, second)
            compose.runOnIdle { scopes.value = "timer-test-A" }
            settled { controller.timer.value?.id == first.id }
            compose.runOnIdle { controller.consume(first.id, "timer-test-B") }
            assertEquals(first.id, controller.timer.value!!.id)
            val blocked = runBlocking { runCatching { vm.repo.commit(listOf(mutation("exam", Exam("wrong-scope", "Must not save", "2027-02-07"))), expectedScope = "another-account") } }
            assertTrue(blocked.isFailure)
            assertFalse(vm.workspace.value.state.exams.any { it.id == "wrong-scope" })
        } finally { worker.cancel(); prefs.edit().remove("timer-test-A").remove("timer-test-B").commit() }
    }

    @Test fun stopwatchStartsWithoutAFormPausesResumesAndLogsOnlyAfterSaving() {
        settled { vm.repo.ready.value }
        val exam = Exam("timer-exam", "Timer exam", "2027-02-07")
        runBlocking { vm.repo.startFresh(); vm.repo.commit(listOf(mutation("exam", exam))) }
        compose.runOnIdle { vm.stopwatch.discard(); vm.select(exam.id); vm.screen.value = Screen.TODAY }
        settled { vm.workspace.value.exam?.id == exam.id }
        compose.onNodeWithText("Start stopwatch").performClick()
        assertNull(vm.editor.value)
        compose.onNodeWithContentDescription("Close focus dial").performClick()
        settled { vm.stopwatch.timer.value != null }
        val id = vm.stopwatch.timer.value!!.id
        compose.onNodeWithText("Pause", useUnmergedTree = true).performClick()
        assertNull(vm.stopwatch.timer.value!!.startedAt)
        assertTrue(vm.workspace.value.sessions.isEmpty())
        compose.runOnIdle { vm.screen.value = Screen.SYLLABUS }
        compose.onNodeWithText("Resume", useUnmergedTree = true).performClick()
        assertNotNull(vm.stopwatch.timer.value!!.startedAt)
        compose.onNodeWithText("Finish & log", useUnmergedTree = true).performClick()
        assertNull(vm.stopwatch.timer.value!!.startedAt)
        compose.onNodeWithTag("Hours").assertTextEquals("0")
        compose.onNodeWithTag("Minutes").assertTextEquals("1")
        compose.onNodeWithContentDescription("Close editor").performClick()
        assertEquals(id, vm.stopwatch.timer.value!!.id)
        assertTrue(vm.workspace.value.sessions.isEmpty())
        compose.onNodeWithText("Finish & log", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("Minutes").performScrollTo().performTextReplacement("25")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.sessions.size == 1 && vm.stopwatch.timer.value == null }
        assertEquals(id, vm.workspace.value.sessions.single().id)
        assertEquals(25, vm.workspace.value.sessions.single().minutes)
        compose.onNodeWithText("Start stopwatch").assertExists()
    }

    @Test fun importanceCanChangeAnytimeAndTopicCreationSupportsImmediateTypingAndEnter() {
        settled { vm.repo.ready.value }
        val exam = Exam("stars-exam", "Importance exam", "2027-02-07")
        runBlocking { vm.repo.startFresh(); vm.repo.commit(listOf(mutation("exam", exam))) }
        compose.runOnIdle { vm.select(exam.id); vm.screen.value = Screen.SYLLABUS }
        settled { vm.workspace.value.exam?.id == exam.id }
        compose.onNodeWithText("Add topic").performClick()
        settled { compose.onAllNodes(hasTestTag("Topic title") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Topic title").performTextInput("Star subject")
        compose.onNodeWithContentDescription("Importance: 5 stars").performScrollTo().performClick()
        compose.onNodeWithTag("Topic title").performScrollTo().performClick().performImeAction()
        settled { vm.workspace.value.topics.size == 1 }
        val root = vm.workspace.value.topics.single(); assertEquals(5, root.importance)
        compose.onNodeWithContentDescription("Actions for Star subject").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Importance: 2 stars").performScrollTo().performClick()
        settled { vm.workspace.value.topics.single().importance == 2 }
        compose.onNodeWithContentDescription("Importance: N/A").performClick()
        settled { vm.workspace.value.topics.single().importance == null }
        compose.onNodeWithContentDescription("Importance: 5 stars").performClick()
        settled { vm.workspace.value.topics.single().importance == 5 }
        compose.onNodeWithText("Add a subtopic").performScrollTo().performClick()
        settled { compose.onAllNodes(hasTestTag("Topic title") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Topic title").performTextInput("Child with own importance")
        compose.onNodeWithText("+ Add child").performScrollTo().performClick()
        settled { compose.onAllNodes(hasTestTag("Topic title 2") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Topic title").assertTextEquals("Child with own importance")
        compose.onNodeWithTag("Topic title 2").performTextInput("Another child in the same list")
        compose.onNodeWithTag("Topic title 2").performImeAction()
        settled { vm.workspace.value.topics.size == 3 }
        val child = vm.workspace.value.topics.single { it.title == "Child with own importance" }; assertNull(child.importance)
        assertTrue(vm.workspace.value.topics.filter { it.id != root.id }.all { it.parentId == root.id })
        compose.runOnIdle { vm.open(Editor.TopicDetail(child.id)) }
        compose.onNodeWithContentDescription("Importance: 4 stars").performScrollTo().performClick()
        settled { vm.workspace.value.topics.single { it.id == child.id }.importance == 4 }
        compose.onNodeWithContentDescription("Close editor").performClick()
        runBlocking { vm.repo.commit(vm.workspace.value.topics.map { mutation("topic", it.copy(reviewOn = vm.workspace.value.day)) }) }
        settled { vm.workspace.value.topics.all { it.reviewOn == vm.workspace.value.day } }
        compose.runOnIdle { vm.screen.value = Screen.REVISION }
        compose.onNodeWithTag("Filter by importance selector").performClick()
        compose.onNodeWithText("★★★★★ · exactly 5").performClick()
        compose.onNodeWithText("Star subject").assertExists()
        compose.onNodeWithText("Child with own importance").assertDoesNotExist()
        compose.runOnIdle { vm.open(Editor.TaskForm(type = "plan")) }
        compose.onNodeWithText("General study · no topic").performScrollTo().performClick()
        compose.onNode(hasTestTag("Filter by importance selector") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("★★★★ · exactly 4").performClick()
        settled { compose.onAllNodesWithText("Child with own importance").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Star subject") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithText("Child with own importance").performClick()
        compose.onNodeWithContentDescription("Close editor").performClick()
        val saved = runBlocking { vm.repo.backup() }
        assertEquals(5, readBackup(saved).single { it.data["id"]?.toString()?.trim('"') == root.id }.data.asModel<Topic>().importance)
        assertTrue(vm.workspace.value.sessions.isEmpty()); assertTrue(vm.workspace.value.topics.all { it.completedOn == null })
        val packet = Topic("deep-packet", exam.id, child.id, "Packet switching", importance = 5)
        runBlocking { vm.repo.commit(listOf(mutation("topic", packet))) }
        settled { vm.workspace.value.topics.any { it.id == packet.id } }
        compose.runOnIdle { vm.open(Editor.LogForm()) }
        compose.onNodeWithText("General study · no topic").performScrollTo().performClick()
        compose.onNodeWithTag("Search topics").assertIsFocused().performTextInput("packet swithcing")
        settled { compose.onAllNodesWithText("Packet switching").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Search topics").performImeAction()
        compose.onNodeWithTag("Hours").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("Minutes").performScrollTo().performTextReplacement("25")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.sessions.size == 1 }
        assertEquals(packet.id, vm.workspace.value.sessions.single().topicId)
        assertEquals("Star subject / Child with own importance / Packet switching", vm.workspace.value.sessions.single().topicPath)
    }

    @Test fun nativeFormsSaveConfidenceCompletionTimeAndRestore() {
        settled { vm.repo.ready.value }
        runBlocking { vm.repo.startFresh() }
        settled { vm.workspace.value.state.exams.isEmpty() }
        compose.onNodeWithText("Create your first study goal").performClick()
        compose.onNodeWithTag("Workspace name").performTextInput("Android test exam")
        compose.waitForIdle()
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.exam?.name == "Android test exam" || vm.workingError.value.isNotBlank() }
        assertEquals("", vm.workingError.value)
        val exam = vm.workspace.value.exam!!
        val outline = "Mathematics\n  Algebra\n    Matrices\n      Determinants"
        runBlocking { vm.repo.commit(outlineTopics(parseOutline(outline), exam.id).map { mutation("topic", if (it.parentId == null) it.copy(lectureCount = 20, testCount = 5) else it) }) }
        settled { vm.workspace.value.topics.size == 4 }
        val leaf = vm.workspace.value.topics.single { it.title == "Determinants" }
        compose.runOnIdle { vm.screen.value = Screen.SYLLABUS }
        compose.onNodeWithTag("Search syllabus").performTextInput("Determinants")
        settled { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 topics")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Syllabus list").performScrollToNode(hasContentDescription("Determinants, 0 of 1 complete"))
        compose.onNodeWithContentDescription("Determinants, 0 of 1 complete").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Lecture 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Lecture 1").performTouchInput { click() }
        compose.onNodeWithText("Lecture 2").performTouchInput { click() }
        compose.onNodeWithText("Tests · 0").performClick()
        compose.onNodeWithText("Test 1").performTouchInput { click() }
        compose.onNodeWithText("Complete & save progress", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.index.total.done == 1 }
        assertEquals(listOf(1, 2), vm.workspace.value.topics.single { it.id == leaf.id }.lectureIds)
        assertEquals(1, vm.workspace.value.index.content(vm.workspace.value.index.subjects.getValue(leaf.id).id).tests.size)
        compose.onNodeWithContentDescription("Actions for Determinants").performScrollTo().performClick()
        compose.onNodeWithTag("Confidence selector").performScrollTo().performClick()
        compose.onNodeWithText("Confident", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.topics.single { it.id == leaf.id }.confidence == "confident" }
        compose.onNodeWithContentDescription("Close editor").performClick()
        compose.runOnIdle { vm.open(Editor.LogForm(topic = leaf.id)) }
        compose.onNodeWithTag("Hours").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("Minutes").performScrollTo().performTextReplacement("25")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.sessions.size == 1 }
        assertEquals(25, vm.workspace.value.sessions.single().minutes)
        assertEquals("Mathematics / Algebra / Matrices / Determinants", vm.workspace.value.sessions.single().topicPath)
        val backup = runBlocking { vm.repo.backup() }
        runBlocking { vm.repo.startFresh(); vm.repo.restore(backup) }
        settled { vm.workspace.value.topics.size == 4 && vm.workspace.value.sessions.size == 1 }
        assertEquals("confident", vm.workspace.value.topics.single { it.id == leaf.id }.confidence)
        assertNotNull(vm.workspace.value.topics.single { it.id == leaf.id }.completedOn)
        assertEquals(listOf(1, 2), vm.workspace.value.topics.single { it.id == leaf.id }.lectureIds)
    }

    @Test fun detailedPlanAndQuickRevisionSaveWithoutInventingStudyTime() {
        settled { vm.repo.ready.value }
        val exam = Exam("plan-exam", "Planning exam", "2027-02-07")
        val root = Topic("plan-subject", exam.id, title = "Mathematics", lectureCount = 30, testCount = 5)
        runBlocking { vm.repo.startFresh(); vm.repo.commit(listOf(mutation("exam", exam), mutation("topic", root))) }
        compose.runOnIdle { vm.select(exam.id) }
        settled { vm.workspace.value.exam?.id == exam.id && vm.workspace.value.topics.size == 1 }
        compose.runOnIdle { vm.open(Editor.TaskForm(type = "plan")) }
        compose.onNodeWithTag("Study block goal").performTextInput("Integration questions")
        compose.onNodeWithTag("Planned duration · minutes").performScrollTo().performTextReplacement("45")
        compose.onNodeWithTag("Detailed steps, resources & outcome").performScrollTo().performTextInput("Solve 15 questions and revise errors")
        compose.onNodeWithTag("Save editor").assertIsDisplayed().performClick()
        settled { vm.workspace.value.tasks.any { it.type == "plan" } || vm.workingError.value.isNotBlank() }
        assertEquals("",vm.workingError.value)
        val plan = vm.workspace.value.tasks.single { it.type == "plan" }
        assertEquals(45, plan.minutes); assertTrue(plan.notes!!.contains("15 questions")); assertTrue(vm.workspace.value.sessions.isEmpty())
        compose.runOnIdle { vm.open(Editor.QuickRevision) }
        compose.onNodeWithText("General study · no topic").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Mathematics").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mathematics").performClick()
        compose.onNodeWithText("Schedule revision", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.topics.single().reviewOn == vm.workspace.value.day || vm.workingError.value.isNotBlank() }
        assertEquals("", vm.workingError.value)
        assertEquals(vm.workspace.value.day, vm.workspace.value.topics.single().reviewOn)
    }

    @Test fun largeSyllabusRemainsVirtualizedAndAllScreensOpen() {
        settled { vm.repo.ready.value }
        val exam = Exam("large-exam", "Large syllabus", "2027-02-07")
        runBlocking {
            vm.repo.startFresh()
            vm.repo.commit(listOf(mutation("exam", exam)) + (0 until 5000).map {
                mutation("topic", Topic("large-$it", exam.id, title = "Topic $it", order = it.toDouble()))
            })
        }
        compose.runOnIdle { vm.select(exam.id); vm.screen.value = Screen.SYLLABUS }
        settled { vm.workspace.value.topics.size == 5000 }
        settled { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "5000 topics")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Syllabus list").performScrollToIndex(5003)
        compose.onNodeWithText("Topic 4999").assertIsDisplayed()
        // LazyColumn should expose a small visible window, not all 5,000 rows.
        assertTrue(compose.onAllNodes(hasContentDescription("Actions for", substring = true)).fetchSemanticsNodes().size < 40)
        Screen.entries.forEach { screen ->
            compose.runOnIdle { vm.screen.value = screen }
            compose.waitForIdle()
        }
        compose.runOnIdle { vm.screen.value=Screen.PLAN }
        compose.onNodeWithTag("Plan content").performScrollToNode(hasText("Scheduling preferences"))
        compose.onNodeWithText("Scheduling preferences").performScrollTo().performClick()
        settled { vm.plannerDialog.value?.examId==exam.id }
        settled { compose.onAllNodesWithTag("Scheduling subjects").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Scheduling subjects").performScrollToIndex(5000)
        compose.onNodeWithText("Topic 4999").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("Automatic lecture pace · 0 disables").fetchSemanticsNodes().size<10)
        compose.onNodeWithText("Close",useUnmergedTree=true).performClick()
    }
}
