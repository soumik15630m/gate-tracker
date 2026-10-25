package org.studyspace.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "records", primaryKeys = ["scope", "id"], indices = [Index(value = ["scope", "kind"])])
data class RecordRow(val scope: String, val id: String, val kind: String, val json: String, val version: Long = 0, val deleted: Boolean = false) {
    fun wire() = WireRecord(id, kind, codec.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonObject }, version, deleted)
}
fun WireRecord.row(scope: String) = RecordRow(scope, id, kind, data.toString(), version, deleted)
@Entity(tableName = "outbox", primaryKeys = ["scope", "id"])
data class OutboxRow(val scope: String, val id: String, val json: String) { fun change() = codec.decodeFromString<Change>(json) }
@Entity(tableName = "conflicts", primaryKeys = ["scope", "id"])
data class ConflictRow(val scope: String, val id: String, val json: String) { fun value() = codec.decodeFromString<Conflict>(json) }
@Entity(tableName = "workspace_meta")
data class WorkspaceMeta(@PrimaryKey val scope: String, val cursor: Long = 0, val sample: Boolean = false)
@Entity(tableName = "recovery")
data class RecoveryPoint(@PrimaryKey val scope: String, val json: String)

@Dao interface StudyDao {
    @Query("SELECT * FROM records WHERE scope = :scope ORDER BY id") fun observeRecords(scope: String): Flow<List<RecordRow>>
    @Query("SELECT * FROM outbox WHERE scope = :scope") fun observePending(scope: String): Flow<List<OutboxRow>>
    @Query("SELECT * FROM conflicts WHERE scope = :scope") fun observeConflicts(scope: String): Flow<List<ConflictRow>>
    @Query("SELECT * FROM workspace_meta WHERE scope = :scope") fun observeMeta(scope: String): Flow<WorkspaceMeta?>
    @Query("SELECT * FROM records WHERE scope = :scope") suspend fun records(scope: String): List<RecordRow>
    @Query("SELECT * FROM records WHERE scope = :scope AND id = :id") suspend fun record(scope: String, id: String): RecordRow?
    @Query("SELECT * FROM outbox WHERE scope = :scope") suspend fun pending(scope: String): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE scope = :scope AND id = :id") suspend fun pending(scope: String, id: String): OutboxRow?
    @Query("SELECT * FROM conflicts WHERE scope = :scope") suspend fun conflicts(scope: String): List<ConflictRow>
    @Query("SELECT * FROM workspace_meta WHERE scope = :scope") suspend fun meta(scope: String): WorkspaceMeta?
    @Query("SELECT * FROM recovery WHERE scope = :scope") suspend fun recovery(scope: String): RecoveryPoint?
    @Upsert suspend fun putRecords(rows: List<RecordRow>)
    @Upsert suspend fun putPending(rows: List<OutboxRow>)
    @Upsert suspend fun putConflicts(rows: List<ConflictRow>)
    @Upsert suspend fun putMeta(row: WorkspaceMeta)
    @Upsert suspend fun putRecovery(row: RecoveryPoint)
    @Query("DELETE FROM outbox WHERE scope = :scope AND id = :id") suspend fun removePending(scope: String, id: String)
    @Query("DELETE FROM conflicts WHERE scope = :scope") suspend fun clearConflicts(scope: String)
    @Query("DELETE FROM records WHERE scope = :scope") suspend fun clearRecords(scope: String)
    @Query("DELETE FROM outbox WHERE scope = :scope") suspend fun clearPending(scope: String)
}
@Database(entities = [RecordRow::class, OutboxRow::class, ConflictRow::class, WorkspaceMeta::class, RecoveryPoint::class], version = 1, exportSchema = true)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun study(): StudyDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, StudyDatabase::class.java, "studyspace-native.db")
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}

@Serializable data class AccountUser(val id: String, val email: String)
@Serializable data class Account(val token: String, val user: AccountUser, val server: String)
fun workspaceKey(server: String, userId: String): String = "account:" + java.security.MessageDigest.getInstance("SHA-256")
    .digest("$server\n$userId".toByteArray()).joinToString("") { "%02x".format(it) }
fun Account.scope() = workspaceKey(server, user.id)

/** Authentication only; study data never contains tokens or passwords. */
class AccountVault(context: Context) {
    private val preferences = context.getSharedPreferences("native_auth", Context.MODE_PRIVATE)
    private val alias = "studyspace.native.auth"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): Account? {
        val payload = preferences.getString("account", null) ?: return null
        val parts = payload.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return codec.decodeFromString(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    fun write(account: Account?) {
        if (account == null) { check(preferences.edit().remove("account").commit()) { "Could not save the sign-out." }; return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(codec.encodeToString(Account.serializer(), account).toByteArray())
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ':' + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString("account", payload).commit()) { "Could not save this sign-in securely." }
    }
}

fun serverAddress(input: String): String {
    val address = input.trim().trimEnd('/')
    val uri = runCatching { URI(address) }.getOrElse { throw IllegalArgumentException("Enter a valid sync server address.") }
    require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.host != null && uri.path.orEmpty().isEmpty()) { "Use the server’s base address, without a path or query." }
    val host = uri.host.lowercase()
    val parts = host.split('.').map { it.toIntOrNull() }
    val privateIp = parts.size == 4 && parts.all { it != null && it in 0..255 } && (parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1]!! in 16..31)
    val local = host == "localhost" || host.endsWith(".local") || privateIp
    require(uri.scheme == "https" || uri.scheme == "http" && local) { "Use HTTPS for a public server. HTTP is supported only on your local network." }
    return address
}

data class LegacyCache(val scope: String, val state: kotlinx.serialization.json.JsonObject, val sample: Boolean)
/** Read the previous app's database without modifying it. Account caches retain their outbox. */
fun readLegacyCaches(context: Context): List<LegacyCache> {
    val path = context.getDatabasePath("RKStorage")
    if (!path.exists()) return emptyList()
    val result = mutableListOf<LegacyCache>()
    SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT key,value FROM catalystLocalStorage WHERE key = ? OR key LIKE ?", arrayOf("studyspace.local.v1", "studyspace.account.%")).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name.endsWith(".restore-point") || name.endsWith(".unreadable")) continue
                val envelope = codec.parseToJsonElement(cursor.getString(1)) as kotlinx.serialization.json.JsonObject
                val state = envelope["state"] as? kotlinx.serialization.json.JsonObject ?: continue
                val scope = if (name == "studyspace.local.v1") "local" else {
                    val suffix = name.removePrefix("studyspace.account.")
                    workspaceKey(URLDecoder.decode(suffix.substringBeforeLast('.'), "UTF-8"), suffix.substringAfterLast('.'))
                }
                val sample = (envelope["sample"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                result.add(LegacyCache(scope, state, sample))
            }
        }
    }
    return result
}
