package org.studyspace.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class NativeSyncTest {
    @Test fun nativeRecordsRoundTripThroughSharedWebApiAndConflictsRemainRecoverable() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("syncServer")
        assumeTrue("Supply syncServer for a dedicated test API", server != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StudyspaceApplication
        val repo = StudyRepository(app, backgroundSync = false)
        repo.initialize()
        withTimeout(20000) { repo.ready.first { it } }
        repo.signOut()
        val email = "native-${uid()}@example.test"
        repo.signIn(server!!, email, "native-test-fixture-password", true)
        try {
            val auth = repo.account.value!!
            val exam = Exam(uid("exam"), "API round trip", "2027-02-07")
            val topic = Topic(uid("topic"), exam.id, title = "Native topic", confidence = "confident", completedOn = "2026-10-25", lectureCount = 8, testCount = 3, lectureIds = listOf(1, 2), testIds = listOf(1))
            val session = StudySession(uid("session"), exam.id, topic.id, topic.title, "2026-10-25", 25)
            val plan = StudyTask(uid("task"), exam.id, "Practice questions", "plan", "2026-10-26", topicId = topic.id, minutes = 45, activity = "Practice", notes = "Review mistakes afterward")
            repo.commit(listOf(mutation("exam", exam), mutation("topic", topic), mutation("session", session), mutation("task", plan)))
            assertTrue(repo.sync())
            suspend fun serverState() = withContext(Dispatchers.IO) { api(auth, SyncRequest(0, emptyList())) }
            val initial = serverState()
            assertEquals(4, initial.records.count { !it.deleted })
            assertEquals(25, initial.records.single { it.kind == "session" }.data.asModel<StudySession>().minutes)
            assertEquals(plan, initial.records.single { it.kind == "task" }.data.asModel<StudyTask>())
            val remoteTopic = initial.records.single { it.id == topic.id }
            withContext(Dispatchers.IO) { api(auth, SyncRequest(initial.cursor, listOf(Change(topic.id, "topic", jsonOf(topic.copy(title = "Web edit")), remoteTopic.version)))) }
            repo.commit(listOf(mutation("topic", topic.copy(title = "Offline native edit"))))
            assertTrue(repo.sync())
            val database = StudyDatabase.open(app)
            try {
                assertEquals(1, database.study().conflicts(auth.scope()).size)
                repo.resolveConflicts(true)
                assertTrue(repo.sync())
                assertTrue(database.study().conflicts(auth.scope()).isEmpty())
                assertTrue(database.study().pending(auth.scope()).isEmpty())
            } finally { database.close() }
            val final = serverState().records.single { it.id == topic.id }.data.asModel<Topic>()
            assertEquals("Offline native edit", final.title)
            assertEquals("confident", final.confidence)
            assertEquals("2026-10-25", final.completedOn)
            assertEquals(listOf(1, 2), final.lectureIds)
        } finally { repo.signOut() }
    }
    private fun api(auth: Account, input: SyncRequest): SyncResult {
        val connection = URL(auth.server + "/api/sync").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${auth.token}")
            connection.outputStream.use { it.write(jsonOf(input).toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
            return codec.decodeFromString(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}
