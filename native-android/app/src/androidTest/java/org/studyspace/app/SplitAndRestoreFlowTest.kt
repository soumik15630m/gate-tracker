package org.studyspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplitAndRestoreFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[NativeViewModel::class.java]
    private fun settled(predicate:()->Boolean) {
        try { compose.waitUntil(30000,predicate) }
        catch(failure:Throwable) { throw AssertionError("Workspace=${vm.workspace.value.exam?.id}, topics=${vm.workspace.value.topics.map { it.id }}, error=${vm.workingError.value}; " + runCatching { compose.onRoot(useUnmergedTree=true).printToString() }.getOrDefault("Tree unavailable"),failure) }
    }
    @Test fun splitLogFindsDeepTopicsAndCountsTheBlockOnlyOnce() {
        settled { vm.repo.ready.value }
        val exam=Exam("split-flow-exam","Split study","2027-02-07")
        val a=Topic("split-flow-a",exam.id,title="Mathematics",lectureCount=3)
        val b=Topic("split-flow-b",exam.id,title="Networks",lectureCount=4)
        val parent=Topic("split-flow-parent",exam.id,b.id,"Switching")
        val leaf=Topic("split-flow-leaf",exam.id,parent.id,"Packet switching")
        runBlocking { vm.repo.signOut();vm.repo.startFresh();vm.repo.commit(listOf(mutation("exam",exam))+listOf(a,b,parent,leaf).map { mutation("topic",it) }) }
        compose.runOnIdle { vm.select(exam.id) }
        settled { vm.workspace.value.exam?.id==exam.id && vm.workspace.value.topics.any { it.id==leaf.id } }
        compose.runOnIdle { vm.open(Editor.LogForm(topic=a.id)) }
        compose.onNodeWithText("+ Add another subject or topic").performScrollTo().performClick()
        compose.onNodeWithTag("Topic selector Find subject or subtopic · entry 2").performScrollTo().performClick()
        compose.onNodeWithTag("Search topics").performTextReplacement("packet swithcing")
        compose.onNodeWithTag("Search topics").performImeAction()
        settled { compose.onAllNodesWithContentDescription("Close topic picker").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Split time equally").performScrollTo().performClick()
        compose.onNodeWithTag("Minutes for entry 1").performScrollTo().assertTextContains("30")
        compose.onNodeWithTag("Minutes for entry 1").performTextReplacement("20")
        compose.onNodeWithTag("Minutes for entry 2").performScrollTo().performTextReplacement("40")
        compose.onNodeWithTag("Save editor").performClick()
        settled { vm.workspace.value.sessions.size==2 || vm.workingError.value.isNotBlank() }
        assertEquals("",vm.workingError.value)
        val entries=vm.workspace.value.sessions
        assertEquals(60,entries.sumOf { it.minutes })
        assertEquals(20,entries.single { it.topicId==a.id }.minutes)
        assertEquals(40,entries.single { it.topicId==leaf.id }.minutes)
        assertEquals(1,entries.map { it.groupId }.distinct().size)
        assertNotNull(entries.first().groupId)
    }
    @Test fun checkpointRestoresRunningAndPausedTimersWithoutResettingTheirIdentity() {
        settled { vm.repo.ready.value }
        val context=compose.activity.applicationContext
        val workspace=MutableStateFlow("update-test-${uid()}")
        val jobs=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            val timer=NativeStopwatch(context,workspace,jobs)
            compose.waitForIdle()
            compose.runOnIdle { timer.start() }
            val running=timer.timer.value!!
            runBlocking { timer.checkpoint() }
            val restored=NativeStopwatch(context,workspace,jobs)
            settled { restored.timer.value?.id==running.id }
            assertEquals(running,restored.timer.value)
            assertTrue(restored.timer.value!!.elapsed()>=running.elapsed(running.startedAt!!))
            compose.runOnIdle { restored.toggle() }
            val paused=restored.timer.value!!
            assertNull(paused.startedAt)
            runBlocking { restored.checkpoint() }
            val again=NativeStopwatch(context,workspace,jobs)
            settled { again.timer.value==paused }
            assertEquals(paused.accumulatedMs,again.timer.value!!.elapsed(System.currentTimeMillis()+600000))
        } finally { jobs.cancel();context.getSharedPreferences("study_stopwatch_v1",0).edit().remove(workspace.value).commit() }
    }
}
