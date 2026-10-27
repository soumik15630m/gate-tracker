package org.studyspace.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable data class ReleaseManifest(val versionCode: Int, val versionName: String, val sha256: String, val apkUrl: String, val notes: String = "")
data class AppUpdateState(val checking: Boolean = false, val progress: Int? = null, val release: ReleaseManifest? = null, val verifiedApk: File? = null, val message: String = "Updates are checked on launch and every five minutes while the app is open. Installation always asks your permission.")

class NativeUpdater(private val context: Context) {
    private val preferences = context.getSharedPreferences("native_updates", 0)
    val state = MutableStateFlow(AppUpdateState())
    private var awaitingInstall = false
    suspend fun checkIfDue() { if (System.currentTimeMillis() - preferences.getLong("checked", 0) > 5 * 60 * 1000) check() }
    suspend fun check() = withContext(Dispatchers.IO) {
        if (state.value.checking || state.value.progress!=null) return@withContext
        state.value = state.value.copy(checking = true, message = "Checking GitHub for a signed native release…")
        try {
            val manifest = codec.decodeFromString<ReleaseManifest>(readHttps("https://github.com/soumik15630m/gate-tracker/releases/download/android-latest/android-release.json?check=${System.currentTimeMillis()/300000}"))
            require(manifest.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "The update checksum is missing." }
            require(manifest.apkUrl.startsWith("https://github.com/soumik15630m/gate-tracker/releases/download/")) { "The release has an unexpected download address." }
            preferences.edit().putLong("checked", System.currentTimeMillis()).apply()
            state.value = if (manifest.versionCode > BuildConfig.VERSION_CODE) if(state.value.release?.versionCode==manifest.versionCode) state.value.copy(checking=false,release=manifest) else AppUpdateState(release = manifest, message = "Version ${manifest.versionName} is available. Your study data stays on this device.") else AppUpdateState(message = "You’re using the latest native release.")
        } catch (_: Exception) { state.value = state.value.copy(checking=false,message = "Could not check for updates. Your installed app works offline.") }
    }
    suspend fun download() = withContext(Dispatchers.IO) {
        val release = state.value.release ?: return@withContext
        if (state.value.progress != null) return@withContext
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "studyspace.partial")
        try {
            state.value = state.value.copy(progress = 0, message = "Downloading the signed APK…")
            val connection = connect(release.apkUrl)
            try {
                val length = connection.contentLengthLong
                require(length <= 128L * 1024 * 1024) { "The update is too large." }
                var received = 0L; var last = -1
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        received += count; require(received <= 128L * 1024 * 1024) { "The update is too large." }
                        output.write(buffer, 0, count)
                        val progress = if (length > 0) (received * 100 / length).toInt().coerceIn(0, 100) else 0
                        if (progress != last) { last = progress; state.value = state.value.copy(progress = progress) }
                    }
                } }
            } finally { connection.disconnect() }
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, true)) { "The download did not pass checksum verification." }
            val packageInfo = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageArchiveInfo(partial.path, PackageManager.GET_SIGNING_CERTIFICATES) else @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(partial.path, PackageManager.GET_SIGNATURES)
            require(packageInfo?.packageName == context.packageName) { "The APK belongs to a different app." }
            val version = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            require(version == release.versionCode.toLong() && version > BuildConfig.VERSION_CODE) { "The APK version does not match the release." }
            val installed = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val downloadedSignatures = if (Build.VERSION.SDK_INT >= 28) packageInfo.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") packageInfo.signatures
            val currentSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") installed.signatures
            require(!downloadedSignatures.isNullOrEmpty() && !currentSignatures.isNullOrEmpty() && downloadedSignatures.any { candidate -> currentSignatures.any { it == candidate } }) { "The APK’s signing certificate does not match your installed app." }
            val verified = File(directory, "studyspace-android.apk")
            if (verified.exists()) check(verified.delete())
            check(partial.renameTo(verified)) { "Could not save the verified update." }
            state.value = state.value.copy(progress = null, verifiedApk = verified, message = "Checksum and signing certificate verified. Install when you’re ready.")
        } catch (error: Exception) { partial.delete(); state.value = state.value.copy(progress = null, verifiedApk = null, message = error.message ?: "The update could not be downloaded.") }
    }
    suspend fun install(): Boolean {
        val file=state.value.verifiedApk ?: return false
        return try {
            val app=context.applicationContext as StudyspaceApplication
            app.repository.readCurrent()
            app.stopwatch.checkpoint()
            app.repository.account.value?.let { app.sharedStopwatch.connect(it);app.sharedStopwatch.checkpoint() }
            withContext(Dispatchers.Main) {
                if(Build.VERSION.SDK_INT>=26 && !context.packageManager.canRequestPackageInstalls()) {
                    awaitingInstall=true
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    state.value=state.value.copy(message="Allow Study Tracker to request installation, then return. Your stopwatch keeps its running or paused state.")
                } else {
                    awaitingInstall=false
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                true
            }
        } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error:Exception) { state.value=state.value.copy(message=error.message ?: "Could not save the stopwatch. The update has been postponed.");false }
    }
    suspend fun updateNow(): Boolean {
        if(state.value.verifiedApk==null) download()
        return if(state.value.verifiedApk!=null) install() else false
    }
    suspend fun resumeInstallation() {
        if(awaitingInstall && state.value.verifiedApk?.exists()==true && (Build.VERSION.SDK_INT<26 || context.packageManager.canRequestPackageInstalls())) install()
    }
    private fun readHttps(address: String): String {
        val connection = connect(address)
        return try { require(connection.contentLengthLong <= 1024 * 1024) { "Update metadata is too large." }; connection.inputStream.bufferedReader().use { it.readText().also { value -> require(value.length <= 1024 * 1024) } } } finally { connection.disconnect() }
    }
    private fun connect(address: String): HttpURLConnection {
        var target = address
        repeat(6) {
            val url = URL(target)
            require(url.protocol == "https" && url.host in setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) { "The update used an unexpected host." }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 30000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Studyspace-Android/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode in 300..399) { val location = connection.getHeaderField("Location"); connection.disconnect(); require(!location.isNullOrEmpty()); target = URL(url, location).toString() }
            else { if (connection.responseCode !in 200..299) { connection.disconnect(); error("Update service is unavailable.") }; return connection }
        }
        error("The update used too many redirects.")
    }
}
