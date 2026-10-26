package org.studyspace.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.work.*
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusPlannerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NativeViewModel::class.java]
    private fun settled(predicate: () -> Boolean) = compose.waitUntil(25000, predicate)

    @Test fun loggingCreatesADeepBranchCreditsLecturesAndCompletesCalendarTarget() {
        settled { vm.repo.ready.value }
        runBlocking { vm.repo.signOut(); vm.repo.startFresh(); vm.repo.commit(listOf(mutation("exam", Exam("focus-exam", "Lecture exam", "2027-02-07")))) }
        compose.runOnIdle { vm.select("focus-exam"); vm.open(Editor.LogForm()); vm.focusDial.value = false }
        settled { vm.workspace.value.exam?.id == "focus-exam" }
        compose.onNodeWithText("+ New subject").performScrollTo().performClick()
        compose.onNodeWithTag("New subject name").performScrollTo().performTextInput("Networks")
        compose.onNodeWithTag("Total lectures").performScrollTo().performTextInput("3")
        compose.onNodeWithText("Add & select",useUnmergedTree=true).performScrollTo().performClick()
        settled { vm.workspace.value.topics.size == 1 }
        val root = vm.workspace.value.topics.single()
        for (name in listOf("Packets", "Packet switching")) {
            compose.onNodeWithText("+ Child under selected topic").performScrollTo().performClick()
            compose.onNodeWithTag("New topic name").performScrollTo().performTextInput(name)
            compose.onNodeWithText("Add & select",useUnmergedTree=true).performScrollTo().performClick()
            settled { vm.workspace.value.topics.any { it.title == name } }
        }
        val leaf = vm.workspace.value.topics.single { it.title == "Packet switching" }
        runBlocking { vm.repo.commit(listOf(mutation("task", StudyTask("day-lectures",root.examId,"Daily Networks","plan",vm.workspace.value.day,topicId=root.id,lectureTarget=2)))) }
        compose.onNodeWithText("Choose completed lectures").performScrollTo().performClick()
        compose.onNodeWithText("Lecture 1").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Lecture 2").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("Hours").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("Minutes").performScrollTo().performTextReplacement("45")
        compose.onNodeWithText("Save",useUnmergedTree=true).performClick()
        settled { vm.workspace.value.sessions.size == 1 || vm.workingError.value.isNotBlank() }
        assertEquals("",vm.workingError.value)
        val session = vm.workspace.value.sessions.single()
        assertEquals(leaf.id,session.topicId); assertEquals(listOf(1,2),session.lectureIds); assertEquals(root.id,session.lectureSubjectId)
        assertEquals("Networks / Packets / Packet switching",session.topicPath)
        assertEquals(2,lectureDay(vm.workspace.value.sessions,root.id,vm.workspace.value.day))
        assertEquals(2,vm.workspace.value.index.content(root.id).lectures.size)
        assertTrue(vm.workspace.value.topics.all { it.completedOn == null })
        compose.runOnIdle { vm.screen.value = Screen.PLAN }
        compose.onNodeWithText("Your lecture calendar").performScrollTo().assertExists()
    }

    @Test fun stopwatchNotificationHasWorkingQuickActionsAndLockscreenChoice() {
        settled { vm.repo.ready.value }
        val context = compose.activity
        val permission = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant org.studyspace.app android.permission.POST_NOTIFICATIONS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        runBlocking { vm.repo.signOut() }
        notificationSettings(context).edit().putBoolean("timer_enabled",true).putBoolean("timer_lock_screen",true).commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            compose.runOnIdle { vm.stopwatch.discard(); vm.stopwatch.start(); vm.notificationOptions.value++ }
            settled { manager.activeNotifications.any { it.id == TIMER_NOTIFICATION_ID && it.notification.actions?.any { a -> a.title.toString() == "Pause" } == true } }
            var notification = manager.activeNotifications.single { it.id == TIMER_NOTIFICATION_ID }.notification
            assertEquals(Notification.VISIBILITY_PUBLIC,notification.visibility)
            notification.actions.single { it.title.toString() == "Pause" }.actionIntent.send()
            settled { vm.stopwatch.timer.value?.startedAt == null }
            settled { manager.activeNotifications.any { it.id == TIMER_NOTIFICATION_ID && it.notification.actions?.any { a -> a.title.toString() == "Resume" } == true } }
            notification = manager.activeNotifications.single { it.id == TIMER_NOTIFICATION_ID }.notification
            notification.actions.single { it.title.toString() == "Resume" }.actionIntent.send()
            settled { vm.stopwatch.timer.value?.startedAt != null }
            notificationSettings(context).edit().putBoolean("timer_lock_screen",false).commit()
            settled { manager.activeNotifications.any { it.id == TIMER_NOTIFICATION_ID && it.notification.visibility == Notification.VISIBILITY_SECRET } }
            manager.activeNotifications.single { it.id == TIMER_NOTIFICATION_ID }.notification.actions.single { it.title.toString() == "Finish & log" }.actionIntent.send()
            settled { vm.editor.value is Editor.LogForm }
            assertNull(vm.stopwatch.timer.value?.startedAt)
        } finally {
            notificationSettings(context).edit().putBoolean("timer_enabled",false).commit()
            context.stopService(Intent(context,TimerNotificationService::class.java))
            compose.runOnIdle { vm.dismiss(); vm.stopwatch.discard(); vm.notificationOptions.value++ }
        }
    }
    @Test fun unfinishedLectureReminderAppearsOnceAndCompletedTargetsStayQuiet() {
        settled { vm.repo.ready.value }
        val context = compose.activity
        val permission = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant org.studyspace.app android.permission.POST_NOTIFICATIONS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        runBlocking { vm.repo.signOut(); vm.repo.startFresh() }
        val day = addDays(today(), -1)
        val root = Topic("reminder-subject", "reminder-exam", title = "Reminder Networks", lectureCount = 3)
        val plan = StudyTask("reminder-target", root.examId, "Daily lectures", "plan", day, topicId = root.id, lectureTarget = 2)
        runBlocking { vm.repo.commit(listOf(mutation("exam", Exam(root.examId,"Reminder exam","2027-02-07")), mutation("topic",root), mutation("task",plan))) }
        val prefs = notificationSettings(context)
        prefs.edit().putBoolean("lecture_reminders",true).remove("reminded:local:$day").commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        val work = WorkManager.getInstance(context)
        try {
            manager.cancel(LECTURE_NOTIFICATION_ID)
            val request = OneTimeWorkRequestBuilder<LectureReminderWorker>().build()
            work.enqueue(request)
            settled { manager.activeNotifications.any { it.id == LECTURE_NOTIFICATION_ID } }
            val notification = manager.activeNotifications.single { it.id == LECTURE_NOTIFICATION_ID }.notification
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Reminder Networks: 2 lectures"))
            manager.cancel(LECTURE_NOTIFICATION_ID)
            val completed = StudySession("reminder-logged",root.examId,root.id,root.title,day,45,lectureIds=listOf(1,2),lectureSubjectId=root.id)
            runBlocking { vm.repo.commit(listOf(mutation("session",completed),mutation("topic",root.copy(lectureIds=listOf(1,2))))) }
            prefs.edit().remove("reminded:local:$day").commit()
            val quiet = OneTimeWorkRequestBuilder<LectureReminderWorker>().build()
            work.enqueue(quiet)
            settled { work.getWorkInfoById(quiet.id).get(2,TimeUnit.SECONDS)?.state?.isFinished == true }
            assertTrue(manager.activeNotifications.none { it.id == LECTURE_NOTIFICATION_ID })
        } finally { prefs.edit().putBoolean("lecture_reminders",false).commit(); manager.cancel(LECTURE_NOTIFICATION_ID); work.cancelAllWorkByTag("lecture-reminders") }
    }

}
