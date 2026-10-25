package org.studyspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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

    @Test fun nativeFormsSaveConfidenceCompletionTimeAndRestore() {
        settled { vm.repo.ready.value }
        runBlocking { vm.repo.startFresh() }
        settled { vm.workspace.value.state.exams.isEmpty() }
        compose.onNodeWithText("Create your first exam").performClick()
        compose.onNodeWithTag("Exam name").performTextInput("Android test exam")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        settled { vm.workspace.value.exam?.name == "Android test exam" }
        val exam = vm.workspace.value.exam!!
        val outline = "Mathematics\n  Algebra\n    Matrices\n      Determinants"
        runBlocking { vm.repo.commit(outlineTopics(parseOutline(outline), exam.id).map { mutation("topic", it) }) }
        settled { vm.workspace.value.topics.size == 4 }
        val leaf = vm.workspace.value.topics.single { it.title == "Determinants" }
        compose.runOnIdle { vm.screen.value = Screen.SYLLABUS }
        compose.onNodeWithTag("Search syllabus").performTextInput("Determinants")
        settled { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 topics")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Syllabus list").performScrollToNode(hasContentDescription("Determinants, 0 of 1 complete"))
        compose.onNodeWithContentDescription("Determinants, 0 of 1 complete").performClick()
        settled { vm.workspace.value.index.total.done == 1 }
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
    }
}
