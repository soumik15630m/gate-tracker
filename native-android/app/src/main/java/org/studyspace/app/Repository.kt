@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.studyspace.app

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class StudyspaceApplication : Application() {
    val repository by lazy { StudyRepository(this) }
    override fun onCreate() { super.onCreate(); repository.initialize() }
}
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = if ((applicationContext as StudyspaceApplication).repository.sync()) Result.success() else Result.retry()
}

class StudyRepository(private val context: Context, private val backgroundSync: Boolean = true) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = StudyDatabase.open(context)
    private val dao = db.study()
    private val vault = AccountVault(context)
    private val writes = Mutex()
    private val syncing = Mutex()
    private var initializing: Job? = null
    private var undo: Pair<String, List<Mutation>>? = null
    private val _account = MutableStateFlow<Account?>(null)
    val account = _account.asStateFlow()
    val workspace = account.map { it?.scope() ?: "local" }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, "local")
    val ready = MutableStateFlow(false)
    val status = MutableStateFlow("On this device")
    val error = MutableStateFlow("")
    val migration = MutableStateFlow("")
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snapshot: StateFlow<StudySnapshot> = workspace.flatMapLatest { key ->
        combine(dao.observeRecords(key).distinctUntilChanged(), dao.observePending(key).distinctUntilChanged(), dao.observeConflicts(key).distinctUntilChanged(), dao.observeMeta(key).distinctUntilChanged()) { records, pending, conflicts, meta ->
            SnapshotInput(records, pending.size, conflicts, meta?.sample ?: false)
        }.mapLatest { input -> withContext(Dispatchers.Default) { StudySnapshot(input.records.map { it.wire() }, input.pending, input.conflicts.map { it.value() }, input.sample) } }
            .transformLatest { initial ->
                var current = initial
                emit(current)
                while (true) {
                    delay(60000)
                    if (today(current.preferences.timezone) != current.today) {
                        current = withContext(Dispatchers.Default) { current.copy() }
                        emit(current)
                    }
                }
            }
    }.catch { error.value = "The workspace could not be opened. Your database is preserved; restore a backup in Settings." }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), StudySnapshot())
    private data class SnapshotInput(val records: List<RecordRow>, val pending: Int, val conflicts: List<ConflictRow>, val sample: Boolean)

    fun initialize() {
        if (initializing != null) return
        initializing = scope.launch {
            try {
                if (dao.meta("local") == null) {
                    migrateOrSeed()
                }
                val restored = runCatching { vault.read() }.getOrElse { error.value = "Saved sign-in could not be read. Sign in again; your study data is preserved."; null }
                if (restored != null) {
                    if (dao.meta(restored.scope()) == null) {
                        dao.putMeta(WorkspaceMeta(restored.scope()))
                    }
                    _account.value = restored
                    schedulePeriodic()
                }
            } catch (e: Exception) { error.value = e.message ?: "Could not open local storage." }
            finally { ready.value = true }
        }
    }
    private suspend fun migrateOrSeed() {
        val legacy = runCatching { readLegacyCaches(context) }
        if (legacy.isFailure) {
            migration.value = "The old app’s database is preserved. Its cache could not be imported; use a JSON backup or sign in again."
            dao.putMeta(WorkspaceMeta("local")); return
        }
        db.withTransaction {
            legacy.getOrDefault(emptyList()).forEach { cache ->
                val records = cache.state["records"]?.jsonObject?.values.orEmpty().map { codec.decodeFromJsonElement<WireRecord>(it) }
                records.forEach { validate(it.kind, it.data) }; validateRelationships(records)
                val pending = cache.state["pending"]?.jsonObject?.values.orEmpty().map { codec.decodeFromJsonElement<Change>(it) }
                val conflicts = cache.state["conflicts"]?.jsonArray.orEmpty().map { codec.decodeFromJsonElement<Conflict>(it) }
                dao.putRecords(records.map { it.row(cache.scope) })
                dao.putPending(pending.map { OutboxRow(cache.scope, it.id, codec.encodeToString(it)) })
                dao.putConflicts(conflicts.map { ConflictRow(cache.scope, it.local.id, codec.encodeToString(it)) })
                dao.putMeta(WorkspaceMeta(cache.scope, cache.state["cursor"]?.jsonPrimitive?.longOrNull ?: 0, cache.sample))
            }
            if (dao.meta("local") == null) {
                val seed = sampleRecords()
                dao.putRecords(seed.map { WireRecord(it.id, it.kind, it.data).row("local") })
                dao.putMeta(WorkspaceMeta("local", sample = true))
            }
        }
        if (legacy.getOrDefault(emptyList()).isNotEmpty()) migration.value = "Previous workspaces imported. Sign in again to reconnect account sync. The original cache is untouched."
    }
    private fun activeScope() = _account.value?.scope() ?: "local"
    private suspend fun current(key: String) = withContext(Dispatchers.Default) {
        StudySnapshot(dao.records(key).map { it.wire() }, dao.pending(key).size, dao.conflicts(key).map { it.value() }, dao.meta(key)?.sample ?: false)
    }
    suspend fun edit(message: String = "Saved", action: (StudySnapshot) -> List<Mutation>) = withContext(Dispatchers.IO) {
        initializing?.join()
        writes.withLock {
            val key = activeScope()
            val before = current(key)
            val changes = withContext(Dispatchers.Default) { action(before) }
            if (changes.isNotEmpty()) commitLocked(key, before.records, changes, rememberUndo = true)
            error.value = ""
        }
        messages.tryEmit(message); scheduleSync()
    }
    suspend fun commit(changes: List<Mutation>, message: String = "Saved") = edit(message) { changes }
    private suspend fun commitLocked(key: String, before: List<WireRecord>, changes: List<Mutation>, rememberUndo: Boolean) {
        require(changes.map { it.id }.distinct().size == changes.size) { "The change contains duplicate records." }
        changes.forEach { validate(it.kind, it.data) }
        val original = before.associateBy { it.id }
        val records = original.toMutableMap()
        val queued = dao.pending(key).associate { it.id to it.change() }
        val output = changes.map { change ->
            val existing = records[change.id]
            WireRecord(change.id, change.kind, change.data, existing?.version ?: 0, change.deleted).also { records[change.id] = it }
        }
        withContext(Dispatchers.Default) { validateRelationships(records.values) }
        require(records.values.count { !it.deleted } <= 20000) { "A workspace supports up to 20,000 records." }
        if (rememberUndo) {
            val previous = before.associateBy { it.id }
            undo = key to changes.map { change -> previous[change.id]?.let { Mutation(it.kind, it.data, it.deleted) } ?: change.copy(deleted = true) }
        }
        db.withTransaction {
            dao.putRecords(output.map { it.row(key) })
            if (_account.value?.scope() == key) dao.putPending(changes.map { change ->
                val existing = original[change.id]
                val pending = Change(change.id, change.kind, change.data, queued[change.id]?.baseVersion ?: existing?.version ?: 0, deleted = change.deleted)
                OutboxRow(key, change.id, codec.encodeToString(pending))
            })
        }
    }
    suspend fun undo() = withContext(Dispatchers.IO) {
        writes.withLock {
            val previous = undo ?: return@withLock
            if (previous.first != activeScope()) return@withLock
            undo = null
            commitLocked(previous.first, dao.records(previous.first).map { it.wire() }, previous.second, false)
            messages.tryEmit("Change undone")
        }; scheduleSync()
    }
    suspend fun toggleTopic(id: String) = edit("Progress updated") { state -> TopicIndex(state.topics, state.sessions).completionChanges(id, state.today) }
    suspend fun resolveConflicts(keepLocal: Boolean) = withContext(Dispatchers.IO) {
        writes.withLock {
            val key = activeScope(); val conflicts = dao.conflicts(key).map { it.value() }
            val records = dao.records(key).map { it.wire() }
            db.withTransaction {
                if (keepLocal) commitLocked(key, records, conflicts.map { Mutation(it.local.kind, it.local.data, it.local.deleted) }, true)
                dao.clearConflicts(key)
            }
        }; scheduleSync(); messages.tryEmit(if (keepLocal) "Your changes are queued for sync" else "Kept the server versions")
    }
    suspend fun backup(): String = withContext(Dispatchers.IO) {
        val records = dao.records(activeScope()).filterNot { it.deleted }.map { BackupRecord(it.kind, it.wire().data) }
        codec.encodeToString(Backup(records = records))
    }
    suspend fun restore(source: String) = withContext(Dispatchers.IO) {
        val imported = withContext(Dispatchers.Default) { readBackup(source) }
        writes.withLock {
            val key = activeScope(); val before = dao.records(key).map { it.wire() }
            dao.putRecovery(RecoveryPoint(key, codec.encodeToString(Backup(records = before.filterNot { it.deleted }.map { BackupRecord(it.kind, it.data) }))))
            val ids = imported.map { it.data.getValue("id").jsonPrimitive.content }.toHashSet()
            val changes = before.filter { !it.deleted && it.id !in ids }.map { Mutation(it.kind, it.data, true) } + imported.map { Mutation(it.kind, it.data, it.deleted) }
            commitLocked(key, before, changes, true)
            val meta = dao.meta(key) ?: WorkspaceMeta(key)
            dao.putMeta(meta.copy(sample = false))
        }; scheduleSync(); messages.tryEmit("Backup restored. A recovery point was saved.")
    }
    suspend fun recover() { val recovery = dao.recovery(activeScope()) ?: error("No recovery point is available yet."); restore(recovery.json) }
    suspend fun startFresh() = withContext(Dispatchers.IO) {
        writes.withLock {
            val key = activeScope(); val before = dao.records(key).map { it.wire() }
            dao.putRecovery(RecoveryPoint(key, codec.encodeToString(Backup(records = before.filterNot { it.deleted }.map { BackupRecord(it.kind, it.data) }))))
            commitLocked(key, before, before.filterNot { it.deleted }.map { Mutation(it.kind, it.data, true) }, true)
            dao.putMeta((dao.meta(key) ?: WorkspaceMeta(key)).copy(sample = false))
        }; scheduleSync(); messages.tryEmit("Fresh workspace ready. A recovery point was saved.")
    }
    suspend fun signIn(server: String, email: String, password: String, register: Boolean) = withContext(Dispatchers.IO) {
        val base = serverAddress(server)
        val response = request(base + if (register) "/api/auth/register" else "/api/auth/login", buildJsonObject { put("email", email.trim()); put("password", password) })
        val auth = Account(response.getValue("token").jsonPrimitive.content, codec.decodeFromJsonElement(response.getValue("user")), base)
        writes.withLock {
            vault.write(auth)
            if (dao.meta(auth.scope()) == null) dao.putMeta(WorkspaceMeta(auth.scope()))
            undo = null; _account.value = auth
        }
        schedulePeriodic(); sync(); messages.tryEmit("Connected to your account")
    }
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val previous = _account.value
        writes.withLock { vault.write(null); _account.value = null; undo = null }
        WorkManager.getInstance(context).cancelUniqueWork("studyspace-periodic-sync")
        if (previous != null) runCatching { request(previous.server + "/api/auth/logout", buildJsonObject {}, previous.token) }
        status.value = "On this device"; messages.tryEmit("Signed out. Your account cache is preserved.")
    }
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        initializing?.join()
        val auth = _account.value ?: return@withContext true
        if (!syncing.tryLock()) return@withContext true
        try {
            val key = auth.scope()
            if (dao.conflicts(key).isNotEmpty()) { status.value = "Review sync conflicts"; return@withContext true }
            status.value = "Syncing…"
            val pending = dao.pending(key).map { it.change() }
            val input = SyncRequest(dao.meta(key)?.cursor ?: 0, pending)
            val result = codec.decodeFromJsonElement<SyncResult>(request(auth.server + "/api/sync", jsonOf(input), auth.token))
            writes.withLock {
                db.withTransaction {
                    for (accepted in result.accepted) {
                        val queued = dao.pending(key, accepted.id)?.change()
                        if (queued?.mutationId == accepted.mutationId) dao.removePending(key, accepted.id)
                        else if (queued != null) dao.putPending(listOf(OutboxRow(key, queued.id, codec.encodeToString(queued.copy(baseVersion = accepted.version)))))
                        dao.record(key, accepted.id)?.let { dao.putRecords(listOf(it.copy(version = accepted.version))) }
                    }
                    for (conflict in result.conflicts) {
                        val local = dao.pending(key, conflict.local.id)?.change() ?: conflict.local
                        dao.removePending(key, local.id)
                        dao.putRecords(listOf(conflict.remote.row(key)))
                        dao.putConflicts(listOf(ConflictRow(key, local.id, codec.encodeToString(Conflict(local, conflict.remote)))))
                    }
                    for (remote in result.records) {
                        val local = dao.record(key, remote.id)
                        val hasPending = dao.pending(key, remote.id) != null
                        dao.putRecords(listOf(if (hasPending && local != null) local.copy(version = remote.version) else remote.row(key)))
                    }
                    val meta = dao.meta(key) ?: WorkspaceMeta(key)
                    if (meta.cursor != result.cursor) dao.putMeta(meta.copy(cursor = result.cursor))
                }
            }
            if (_account.value == auth) { status.value = if (result.conflicts.isEmpty()) "Synced" else "Review sync conflicts"; error.value = "" }
            true
        } catch (e: Exception) {
            if (_account.value == auth) { status.value = "Saved offline"; error.value = e.message ?: "Sync is unavailable. Changes remain saved on this device." }
            false
        } finally { syncing.unlock() }
    }
    private fun schedulePeriodic() {
        if (!backgroundSync) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("studyspace-periodic-sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    private fun scheduleSync() {
        if (!backgroundSync) return
        if (_account.value == null) return
        WorkManager.getInstance(context).enqueueUniqueWork("studyspace-edit-sync", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(1, TimeUnit.SECONDS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    private fun request(address: String, body: JsonObject, token: String? = null): JsonObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val success = connection.responseCode in 200..299
            val stream = if (success) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val value = runCatching { codec.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!success) throw IllegalStateException(value?.get("error")?.jsonPrimitive?.content ?: "The server could not complete this request (${connection.responseCode}).")
            return value ?: error("The sync server returned an unreadable response.")
        } finally { connection.disconnect() }
    }
}

fun sampleRecords(): List<Mutation> {
    val day = today(); val exam = Exam("sample-exam", "GATE preparation", addDays(day, 106), 20.0, description = "Small, deliberate steps toward something bigger.")
    val topics = listOf(
        Topic("math", exam.id, title = "Engineering Mathematics", order = 0.0),
        Topic("calculus", exam.id, "math", "Calculus", 0.0),
        Topic("limits", exam.id, "calculus", "Limits & continuity", 0.0, addDays(day, -4), "confident", day),
        Topic("integration", exam.id, "calculus", "Integration", 1.0),
        Topic("indefinite", exam.id, "integration", "Indefinite integrals", 0.0, addDays(day, -2), "confident"),
        Topic("definite", exam.id, "integration", "Definite integrals", 1.0),
        Topic("properties", exam.id, "definite", "Properties & theorems", 0.0, confidence = "getting-there", focusOn = day),
        Topic("practice", exam.id, "definite", "Practice questions", 1.0, focusOn = day),
        Topic("linear", exam.id, "math", "Linear algebra", 1.0),
        Topic("matrices", exam.id, "linear", "Matrices & determinants", 0.0, addDays(day, -5), "confident"),
        Topic("eigen", exam.id, "linear", "Eigenvalues & eigenvectors", 1.0, confidence = "needs-work", reviewOn = addDays(day, -1)),
        Topic("algorithms", exam.id, title = "Algorithms", order = 1.0),
        Topic("sorting", exam.id, "algorithms", "Sorting & searching", 0.0, addDays(day, -3), "confident"),
        Topic("dynamic", exam.id, "algorithms", "Dynamic programming", 1.0),
        Topic("graphs", exam.id, "algorithms", "Graph algorithms", 2.0),
        Topic("os", exam.id, title = "Operating Systems", order = 2.0),
        Topic("processes", exam.id, "os", "Processes & threads", 0.0, addDays(day, -5), "confident"),
        Topic("memory", exam.id, "os", "Memory management", 1.0),
        Topic("deadlocks", exam.id, "os", "Deadlocks", 2.0))
    val leaves = topics.filter { topic -> topics.none { it.parentId == topic.id } }
    val sessions = (0..83).filterNot { it > 8 && (it % 7 == 0 || it % 11 == 0) }.map { offset ->
        val topic = leaves[(offset * 3) % leaves.size]
        StudySession("sample-session-$offset", exam.id, topic.id, topic.title, addDays(day, -offset.toLong()), if (offset == 0) 100 else 55 + offset % 6 * 20, if (offset % 4 == 0) "Revision" else "Learning")
    }
    val mocks = listOf(42.0, 48.0, 51.0, 59.0, 64.0).mapIndexed { i, score -> MockTest("sample-mock-$i", exam.id, "Full syllabus · ${i + 1}", "Full syllabus", addDays(day, -35L + i * 7), score, mistakes = if (i == 4) listOf(Mistake("eigen", "Concept gap", "Revisit diagonalisation.")) else emptyList()) }
    return listOf(mutation("exam", exam), mutation("preferences", Preferences())) + topics.map { mutation("topic", it) } + sessions.map { mutation("session", it) } + mocks.map { mutation("mock", it) } + listOf(mutation("task", StudyTask("sample-milestone", exam.id, "Complete the first syllabus pass", "milestone", addDays(day, 45))))
}
