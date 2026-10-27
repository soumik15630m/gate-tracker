package org.studyspace.app

import androidx.compose.ui.test.*
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated disposable API and emulator only. */
@RunWith(AndroidJUnit4::class)
class SharedTimerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NativeViewModel::class.java]
    private fun settled(predicate: () -> Boolean) = compose.waitUntil(20000, predicate)
    @Test fun webStartAppearsInPhoneHeaderAndEitherDeviceCanControlAndLogIt() {
        val server = InstrumentationRegistry.getArguments().getString("syncServer")
        assumeTrue("Supply a dedicated syncServer", server != null)
        settled { vm.repo.ready.value }
        val context=compose.activity
        val permission=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant org.studyspace.app android.permission.POST_NOTIFICATIONS")
        ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        notificationSettings(context).edit().putBoolean("timer_enabled",true).putBoolean("timer_lock_screen",true).commit()
        val manager=context.getSystemService(NotificationManager::class.java)
        val repository=vm.repo
        val controller=vm.sharedStopwatch
        fun waitForNotification(action:String)=runBlocking { withTimeout(20000) { while(manager.activeNotifications.none { it.id==TIMER_NOTIFICATION_ID && it.notification.actions?.any { a -> a.title.toString()==action }==true }) delay(100) } }
        runBlocking { vm.repo.signOut(); vm.repo.signIn(server!!, "shared-timer-${uid()}@example.test", "shared-timer-fixture-password", true) }
        try {
            val auth = vm.repo.account.value!!
            val exam = Exam(uid("exam"), "Shared timer exam", "2027-02-07")
            runBlocking { vm.repo.commit(listOf(mutation("exam", exam))); assertTrue(vm.repo.sync()) }
            compose.runOnIdle { vm.select(exam.id); vm.screen.value = Screen.TODAY }
            settled { vm.sharedStopwatch.connected.value && vm.sharedStopwatch.state.value?.version == 0L }
            fun web(action: String): SharedTimerState = runBlocking {
                val current = repository.timerRequest(auth, buildJsonObject { put("action", "read") })
                repository.timerRequest(auth, buildJsonObject { put("action", action); put("version", current.version); put("device", "Web"); current.timer?.let { put("id", it.id) } })
            }
            val started = web("start")
            settled { vm.sharedStopwatch.state.value?.timer?.id == started.timer!!.id }
            compose.onNodeWithText("One timer running · started on Web").assertExists()
            compose.onNodeWithContentDescription("Pause shared timer").performClick()
            settled { vm.sharedStopwatch.state.value?.timer?.startedAt == null && !vm.sharedStopwatch.busy.value }
            val paused = runBlocking { vm.repo.timerRequest(auth, buildJsonObject { put("action", "read") }) }
            assertEquals("Android", paused.timer!!.updatedBy); assertNull(paused.timer.startedAt)
            waitForNotification("Resume")
            val pausedNotice=manager.activeNotifications.single { it.id==TIMER_NOTIFICATION_ID }.notification
            assertEquals(Notification.VISIBILITY_PUBLIC,pausedNotice.visibility)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT,manager.getNotificationChannel(TIMER_CHANNEL).importance)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            web("resume")
            waitForNotification("Pause")
            val resumedNotice=manager.activeNotifications.single { it.id==TIMER_NOTIFICATION_ID }.notification
            assertTrue(resumedNotice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(resumedNotice.publicVersion.actions.any { it.title.toString()=="Pause" })
            assertNotNull(controller.state.value?.timer?.startedAt)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            settled { vm.sharedStopwatch.state.value?.timer?.startedAt != null }
            compose.onNodeWithContentDescription("Shared timer actions").performClick()
            compose.onNodeWithText("Finish & log").performClick()
            settled { vm.editor.value is Editor.LogForm }
            compose.onNodeWithTag("Hours").performScrollTo().performTextReplacement("0")
            compose.onNodeWithTag("Minutes").performScrollTo().performTextReplacement("25")
            compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
            settled { vm.workspace.value.sessions.any { it.id == started.timer!!.id } || vm.workingError.value.isNotBlank() }
            assertEquals("", vm.workingError.value)
            assertEquals(25, vm.workspace.value.sessions.single { it.id == started.timer!!.id }.minutes)
            assertNull(runBlocking { vm.repo.timerRequest(auth, buildJsonObject { put("action", "read") }) }.timer)
            settled { vm.sharedStopwatch.state.value?.timer == null && !vm.sharedStopwatch.busy.value }
            compose.onNodeWithText("Start stopwatch").performClick()
            compose.onNodeWithContentDescription("Close focus dial").performClick()
            settled { vm.sharedStopwatch.state.value?.timer?.origin == "Android" }
            web("pause")
            settled { vm.sharedStopwatch.state.value?.timer?.startedAt == null }
            compose.onNodeWithText("Paused · started on Android").assertExists()
            compose.onNodeWithContentDescription("Resume shared timer").assertExists()
            assertEquals(1, vm.workspace.value.sessions.size)
            web("discard")
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            notificationSettings(context).edit().putBoolean("timer_enabled",false).commit()
            context.stopService(Intent(context,TimerNotificationService::class.java))
            runBlocking { repository.signOut() }
        }
    }
}
