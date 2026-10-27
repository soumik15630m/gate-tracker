package org.studyspace.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.flow.first
import java.time.*
import java.util.concurrent.TimeUnit

const val LECTURE_NOTIFICATION_ID = 9402
fun scheduleLectureReminders(context: Context) {
    val prefs = notificationSettings(context); val work = WorkManager.getInstance(context)
    if (!prefs.getBoolean("lecture_reminders", false)) { work.cancelUniqueWork("lecture-evening-reminders"); NotificationManagerCompat.from(context).cancel(LECTURE_NOTIFICATION_ID); return }
    val now = ZonedDateTime.now(ZoneId.of(prefs.getString("reminder_timezone", ZoneId.systemDefault().id)))
    var next = now.withHour(prefs.getInt("reminder_hour", 21)).withMinute(prefs.getInt("reminder_minute", 0)).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusDays(1)
    val request = PeriodicWorkRequestBuilder<LectureReminderWorker>(24, TimeUnit.HOURS).setInitialDelay(Duration.between(now,next).toMillis(), TimeUnit.MILLISECONDS).addTag("lecture-reminders").build()
    work.enqueueUniquePeriodicWork("lecture-evening-reminders", ExistingPeriodicWorkPolicy.UPDATE, request)
}

data class MissingLectureDay(val date: String, val subject: String, val remaining: Int)
fun missingLectureDays(state: StudySnapshot, day: String, includeToday: Boolean): List<MissingLectureDay> = state.tasks.filter { it.lectureTarget != null && it.carriedOn == null && (it.date < day || includeToday && it.date == day) }.mapNotNull { plan ->
    val subject = state.topics.firstOrNull { it.id == plan.topicId && it.parentId == null } ?: return@mapNotNull null
    val remaining = ((plan.lectureTarget ?: 0) - lectureDay(state.sessions, subject.id, plan.date) - (plan.carriedCount ?: 0)).coerceAtLeast(0)
    if (remaining == 0) null else MissingLectureDay(plan.date, subject.title, remaining)
}

class LectureReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val prefs = notificationSettings(applicationContext)
        if (!prefs.getBoolean("lecture_reminders", false) || !notificationsAllowed(applicationContext)) return Result.success()
        val repo = (applicationContext as StudyspaceApplication).repository
        repo.ready.first { it }; val owner = repo.account.value?.scope() ?: "local"
        if (!repo.sync() && repo.account.value != null) return Result.retry()
        val state = repo.readCurrent()
        if ((repo.account.value?.scope() ?: "local") != owner) return Result.success()
        val now = ZonedDateTime.now(ZoneId.of(state.preferences.timezone))
        val dueToday = now.hour * 60 + now.minute >= prefs.getInt("reminder_hour",21) * 60 + prefs.getInt("reminder_minute",0)
        val missing = missingLectureDays(state, now.toLocalDate().toString(), dueToday).filter { !prefs.getBoolean("reminded:$owner:${it.date}", false) }
        if (missing.isEmpty()) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("lecture_plan_reminders", "Daily lecture plan", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "An evening nudge for unfinished subject lecture targets" })
        val text = missing.take(5).joinToString("\n") { "${it.subject}: ${it.remaining} lectures · ${it.date}" }
        val open = PendingIntent.getActivity(applicationContext, 201, timerActivityIntent(applicationContext, "plan", "", owner), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "lecture_plan_reminders").setSmallIcon(R.drawable.ic_timer_notification).setContentTitle("A little room for your lecture plan")
            .setContentText("${missing.sumOf { it.remaining }} planned lectures still to complete").setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_REMINDER).addAction(android.R.drawable.ic_menu_my_calendar, "Open planner", open).build()
        if (!postStudyNotification(applicationContext, LECTURE_NOTIFICATION_ID, notification)) return Result.success()
        prefs.edit().apply { missing.map { it.date }.distinct().forEach { putBoolean("reminded:$owner:$it", true) } }.apply()
        return Result.success()
    }
}

@Composable fun LectureReminderOption(vm: NativeViewModel) {
    val context = LocalContext.current; val prefs = remember { notificationSettings(context) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("lecture_reminders", false)) }
    var hour by remember { mutableIntStateOf(prefs.getInt("reminder_hour",21)) }; var minute by remember { mutableIntStateOf(prefs.getInt("reminder_minute",0)) }
    fun apply(value: Boolean) { enabled = value; prefs.edit().putBoolean("lecture_reminders",value).putString("reminder_timezone", vm.workspace.value.state.preferences.timezone).apply(); scheduleLectureReminders(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> apply(granted); if (!granted) vm.repo.messages.tryEmit("Allow notifications to receive lecture plan reminders.") }
    ToggleLine("Remind me about unfinished lectures", enabled) { value -> if (value && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else apply(value) }
    if (enabled) {
        Action("Reminder time · ${hour.toString().padStart(2,'0')}:${minute.toString().padStart(2,'0')}", secondary = true) { TimePickerDialog(context, { _, h, m -> hour = h; minute = m; prefs.edit().putInt("reminder_hour",h).putInt("reminder_minute",m).apply(); scheduleLectureReminders(context) },hour,minute,true).show() }
        Body("An evening nudge for today’s remaining targets and missed days. Android schedules delivery approximately; battery settings may delay it.")
    }
}
