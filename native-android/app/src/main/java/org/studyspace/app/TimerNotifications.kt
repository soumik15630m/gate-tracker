package org.studyspace.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

const val TIMER_NOTIFICATION_ID = 9401
const val TIMER_CHANNEL = "study_stopwatch_live_v2"
fun notificationSettings(context: Context) = context.getSharedPreferences("notification_options", Context.MODE_PRIVATE)
fun notificationsAllowed(context: Context) = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
fun postStudyNotification(context: Context, id: Int, notification: android.app.Notification): Boolean = try {
    if (!notificationsAllowed(context)) false else { NotificationManagerCompat.from(context).notify(id, notification); true }
} catch (_: SecurityException) { false }
fun timerActivityIntent(context: Context, action: String, id: String, scope: String): Intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    putExtra("timerAction", action); putExtra("timerId", id); putExtra("timerScope", scope)
}
fun timerNotification(context: Context, timer: StudyTimer?, scope: String, offset: Long, failure: String? = null): android.app.Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(TIMER_CHANNEL, "Live study stopwatch", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Quiet live stopwatch with lock-screen controls"; setSound(null, null); enableVibration(false); setShowBadge(false); lockscreenVisibility=android.app.Notification.VISIBILITY_PUBLIC })
    val visible = notificationSettings(context).getBoolean("timer_lock_screen", true)
    val id = timer?.id.orEmpty(); val open = PendingIntent.getActivity(context, 101, timerActivityIntent(context, "focus", id, scope), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder = NotificationCompat.Builder(context, TIMER_CHANNEL).setSmallIcon(R.drawable.ic_timer_notification).setContentTitle(if (timer == null) "Connecting your study stopwatch" else if (timer.startedAt == null) "Study stopwatch paused" else "Your focus is adding up")
        .setContentText(failure ?: timer?.let { if (it.startedAt == null) timerClock(it.elapsed(System.currentTimeMillis() + offset)) else "Started on ${it.origin ?: "this device"} · a little further, every day" } ?: "Restoring your active timer")
        .setContentIntent(open).setPriority(NotificationCompat.PRIORITY_DEFAULT).setOnlyAlertOnce(true).setOngoing(true).setCategory(NotificationCompat.CATEGORY_STOPWATCH).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setVisibility(if (visible) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET)
    if (timer != null) {
        val running = timer.startedAt != null
        builder.setWhen(System.currentTimeMillis() - timer.elapsed(System.currentTimeMillis() + offset)).setShowWhen(running).setUsesChronometer(running)
        val action = if (running) "pause" else "resume"
        val control = Intent(context, TimerNotificationService::class.java).apply { this.action = action; putExtra("timerId", id); putExtra("timerScope", scope) }
        val pending = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 102, control, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else PendingIntent.getService(context, 102, control, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (running) "Pause" else "Resume", pending)
        builder.addAction(android.R.drawable.ic_menu_save, "Finish & log", PendingIntent.getActivity(context, 103, timerActivityIntent(context, "finish", id, scope), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
    if(visible) builder.setPublicVersion(builder.build())
    return builder.build()
}

class TimerNotificationService : Service() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private fun keepSharedTimerAwake() {
        val lock=wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"StudyTracker:SharedTimer").also { wakeLock=it }
        if(!lock.isHeld) lock.acquire(10*60*1000L)
    }
    private fun releaseWakeLock() { wakeLock?.let { if(it.isHeld) it.release() }; wakeLock=null }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as StudyspaceApplication
        val knownAccount = app.repository.account.value
        val knownTimer = if (knownAccount == null) app.stopwatch.timer.value else app.sharedStopwatch.state.value?.timer
        startForeground(TIMER_NOTIFICATION_ID, timerNotification(this, knownTimer, knownAccount?.scope() ?: "local", if (knownAccount == null) 0 else app.sharedStopwatch.clockOffset.value))
        if (!notificationSettings(this).getBoolean("timer_enabled", false) || !notificationsAllowed(this)) { stopSelf(); return START_NOT_STICKY }
        intent?.action?.takeIf { it in listOf("pause", "resume") }?.let { action -> jobs.launch {
            app.repository.ready.first { it }
            val auth = app.repository.account.value; val scope = auth?.scope() ?: "local"
            if (intent.getStringExtra("timerScope") != scope) return@launch
            try {
                if (auth != null) { app.sharedStopwatch.connect(auth); app.sharedStopwatch.poll(); val state = app.sharedStopwatch.state.value; if (state != null && state.timer?.id == intent.getStringExtra("timerId")) app.sharedStopwatch.command(action, state) }
                else if (app.stopwatch.timer.value?.id == intent.getStringExtra("timerId")) { val running = app.stopwatch.timer.value?.startedAt != null; if (action == "pause" && running || action == "resume" && !running) app.stopwatch.toggle() }
            } catch (_: Exception) { /* Preserve the last state; the ongoing notification reports connectivity. */ }
            if (notificationSettings(this@TimerNotificationService).getBoolean("timer_enabled",false) && notificationsAllowed(this@TimerNotificationService) && (app.repository.account.value?.scope() ?: "local") == scope) {
                val current = if (auth == null) app.stopwatch.timer.value else app.sharedStopwatch.state.value?.timer
                if (current != null) postStudyNotification(this@TimerNotificationService, TIMER_NOTIFICATION_ID, timerNotification(this@TimerNotificationService, current, scope, if (auth == null) 0 else app.sharedStopwatch.clockOffset.value))
            }
        } }
        if (watch?.isActive != true) watch = jobs.launch {
            app.repository.ready.first { it }
            val owner = app.repository.account.value?.scope() ?: "local"
            val observer=launch {
                combine(app.stopwatch.timer,app.sharedStopwatch.state,app.sharedStopwatch.clockOffset,app.sharedStopwatch.connected) { local,shared,offset,connected ->
                    val account=app.repository.account.value
                    val timer=if(account==null) local else shared?.timer
                    Triple(timer,if(account==null) 0L else offset,account!=null && !connected)
                }.collect { (timer,offset,offline) ->
                    if(timer!=null && (app.repository.account.value?.scope() ?: "local")==owner && notificationsAllowed(this@TimerNotificationService)) postStudyNotification(this@TimerNotificationService,TIMER_NOTIFICATION_ID,timerNotification(this@TimerNotificationService,timer,owner,offset,if(offline) "Reconnecting to your shared timer" else null))
                }
            }
            try {
                while (isActive && notificationSettings(this@TimerNotificationService).getBoolean("timer_enabled", false) && notificationsAllowed(this@TimerNotificationService)) {
                    val auth = app.repository.account.value
                    if ((auth?.scope() ?: "local") != owner) break
                    if(auth!=null) {
                        keepSharedTimerAwake()
                        app.sharedStopwatch.connect(auth)
                        try { app.sharedStopwatch.poll() } catch(cancelled:CancellationException) { throw cancelled } catch (_: Exception) {}
                    }
                    val timer = if (auth == null) app.stopwatch.timer.value else app.sharedStopwatch.state.value?.timer
                    if (timer == null) break
                    if(timer.finished || auth==null) releaseWakeLock()
                    val failure = if (auth != null && !app.sharedStopwatch.connected.value) "Reconnecting to your shared timer" else null
                    postStudyNotification(this@TimerNotificationService, TIMER_NOTIFICATION_ID, timerNotification(this@TimerNotificationService, timer, owner, if(auth==null) 0 else app.sharedStopwatch.clockOffset.value, failure))
                    delay(5000)
                }
            } finally { observer.cancel();releaseWakeLock();stopSelf() }
        }
        return START_STICKY
    }
    override fun onDestroy() { jobs.cancel(); releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}

@Composable fun TimerNotificationOption(vm: NativeViewModel) {
    val context = LocalContext.current
    val prefs = remember { notificationSettings(context) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("timer_enabled", false)) }
    var locked by remember { mutableStateOf(prefs.getBoolean("timer_lock_screen", true)) }
    fun apply(value: Boolean) { enabled = value; prefs.edit().putBoolean("timer_enabled", value).apply(); vm.notificationOptions.value++; if (!value) context.stopService(Intent(context, TimerNotificationService::class.java)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> apply(granted); if (!granted) vm.repo.messages.tryEmit("Notification permission was not granted. The stopwatch still works in the app.") }
    ToggleLine("Stopwatch notification & quick actions", enabled) { value -> if (value && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else apply(value) }
    if (enabled) {
        ToggleLine("Show stopwatch on lock screen", locked) { locked = it; prefs.edit().putBoolean("timer_lock_screen", it).apply(); vm.notificationOptions.value++ }
        Body("The notification follows web pause/resume changes while this timer is active, including with the screen off. Finish & log opens your entry form.")
        Action("Notification & lock-screen settings",secondary=true) {
            val intent=if(Build.VERSION.SDK_INT>=26) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,TIMER_CHANNEL) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}"))
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Body("Enable lock-screen visibility in the phone’s notification settings. HyperOS may also require background activity to be allowed for Study Tracker.")
    }
}

@Composable fun TimerNotificationBridge(vm: NativeViewModel) {
    val context = LocalContext.current
    val account by vm.repo.account.collectAsStateWithLifecycle()
    val local by vm.stopwatch.timer.collectAsStateWithLifecycle()
    val shared by vm.sharedStopwatch.state.collectAsStateWithLifecycle()
    val options by vm.notificationOptions.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val timer = if (account == null) local else shared?.timer
    LaunchedEffect(account, timer?.id, options) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (notificationSettings(context).getBoolean("timer_enabled", false) && notificationsAllowed(context) && timer != null) {
                try { ContextCompat.startForegroundService(context, Intent(context, TimerNotificationService::class.java)) } catch (error: Exception) { vm.repo.messages.emit("Could not show the timer notification. Open notification settings to check permissions.") }
            } else context.stopService(Intent(context, TimerNotificationService::class.java))
        }
    }
}
