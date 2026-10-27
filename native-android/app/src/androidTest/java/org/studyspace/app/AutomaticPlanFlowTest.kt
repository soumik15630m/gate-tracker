package org.studyspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticPlanFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[NativeViewModel::class.java]
    private fun settled(predicate:()->Boolean)=compose.waitUntil(30000,predicate)
    @Test fun subjectTotalEditsRebuildPlansAndPracticeLoggingUpdatesTheAgenda() {
        settled { vm.repo.ready.value }
        val day=today();val end=addDays(day,14)
        val exam=Exam("automatic-exam","Study tracker flow",end,goalType="course",schedule=Schedule(day,weekdays=listOf(0,1,2,3,4,5,6),dailyLimit=4))
        val root=Topic("automatic-root",exam.id,title="Networks",lectureCount=8,schedulePace=2,importance=3)
        val leaf=Topic("automatic-child",exam.id,root.id,"Packet switching",importance=5)
        runBlocking { vm.repo.signOut();vm.repo.startFresh();vm.repo.commit(listOf(mutation("exam",exam),mutation("topic",root),mutation("topic",leaf))) }
        compose.runOnIdle { vm.select(exam.id);vm.open(Editor.TopicForm(root)) }
        settled { vm.workspace.value.tasks.size==4 }
        compose.onNodeWithTag("Lecture count").performScrollTo().performTextReplacement("12")
        compose.onNodeWithText("Save",useUnmergedTree=true).performClick()
        settled { vm.workspace.value.topics.firstOrNull { it.id==root.id }?.lectureCount==12 || vm.workingError.value.isNotBlank() }
        assertEquals("",vm.workingError.value)
        settled { vm.workspace.value.tasks.sumOf { it.lectureTarget ?: 0 }==12 }
        val locked=vm.workspace.value.tasks.first { it.date==addDays(day,1) }
        runBlocking { vm.repo.commit(listOf(mutation("task",locked.copy(locked=true)))) }
        compose.runOnIdle { vm.open(Editor.LogForm(topic=leaf.id,initialActivity="Practice")) }
        compose.onNodeWithText("Choose completed lectures").performScrollTo().performClick()
        compose.onNodeWithTag("Lecture number list").performScrollTo().performScrollToIndex(0)
        compose.onNodeWithText("Lecture 1").performTouchInput { click() }
        compose.onNodeWithTag("Lecture number list").performScrollToIndex(1)
        compose.onNodeWithText("Lecture 2").performTouchInput { click() }
        compose.onNodeWithText("Record question practice").performScrollTo().performClick()
        compose.onNodeWithTag("Correct answers").performScrollTo().performTextReplacement("8")
        compose.onNodeWithTag("Incorrect answers").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("Skipped questions").performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("Save",useUnmergedTree=true).performClick()
        settled { vm.workspace.value.sessions.size==1 || vm.workingError.value.isNotBlank() }
        assertEquals("",vm.workingError.value)
        val state=vm.workspace.value
        assertEquals(Practice(8,2,3),state.sessions.single().practice)
        assertEquals(80,practiceStats(state.sessions).accuracy)
        assertEquals(2,lectureDay(state.sessions,root.id,day))
        assertTrue(state.tasks.first { it.id==locked.id }.locked)
        assertEquals(12,state.tasks.sumOf { it.lectureTarget ?: 0 })
        assertEquals(5,state.topics.first { it.id==leaf.id }.importance)
        assertTrue(state.topics.all { it.completedOn==null })
        compose.runOnIdle { vm.screen.value=Screen.TODAY }
        compose.onNodeWithTag("Today content").performScrollToNode(hasText("Your daily agenda"))
        compose.onNodeWithText("Your daily agenda").assertExists()
        compose.runOnIdle { vm.screen.value=Screen.PLAN }
        compose.onNodeWithTag("Plan content").performScrollToNode(hasText("Scheduling preferences"))
        compose.onNodeWithText("Scheduling preferences").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        settled { vm.plannerDialog.value?.examId==exam.id }
        settled { compose.onAllNodesWithText("Your study rhythm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Your study rhythm").assertExists()
        compose.onNodeWithText("Close",useUnmergedTree=true).performClick()
    }
}
