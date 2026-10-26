package org.studyspace.app

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable fun TimerFocusDialog(vm: NativeViewModel) {
    val open by vm.focusDial.collectAsStateWithLifecycle()
    if (!open) return
    val auth by vm.repo.account.collectAsStateWithLifecycle()
    val local by vm.stopwatch.timer.collectAsStateWithLifecycle()
    val shared by vm.sharedStopwatch.state.collectAsStateWithLifecycle()
    val online by vm.sharedStopwatch.connected.collectAsStateWithLifecycle()
    val remoteBusy by vm.sharedStopwatch.busy.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.workingError.collectAsStateWithLifecycle()
    val offset by vm.sharedStopwatch.clockOffset.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val timer = if (auth == null) local else shared?.timer
    val running = timer?.startedAt != null
    LaunchedEffect(timer) { now = System.currentTimeMillis(); if (running) while (true) { delay(1000); now = System.currentTimeMillis() } }
    val elapsed = timer?.elapsed(now + if (auth == null) 0 else offset) ?: 0
    fun action(name: String) {
        if (auth == null) {
            when (name) { "start" -> vm.stopwatch.start(); "pause", "resume" -> vm.stopwatch.toggle(); "finish" -> vm.stopwatch.finish()?.let { vm.focusDial.value = false; vm.open(Editor.LogForm(timer = it, timerScope = "local")) } }
        } else vm.perform(false) {
            val result = vm.sharedStopwatch.command(name)
            if (name == "finish" && vm.repo.account.value == auth) result.timer?.let { vm.focusDial.value = false; vm.open(Editor.LogForm(timer = it, timerScope = auth!!.scope(), timerVersion = result.version)) }
        }
    }
    Dialog(onDismissRequest = { vm.focusDial.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).background(Palette.background).navigationBarsPadding().padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Make room for focus.", fontFamily = Serif, fontSize = 29.sp, color = Palette.text, modifier = Modifier.weight(1f)); IconAction("Close focus dial", Icons.Outlined.Close) { vm.focusDial.value = false } }
            TimerDial(elapsed, running, if (timer == null) "READY WHEN YOU ARE" else if (running) "STUDYING" else "PAUSED")
            Text(if (!running) if (elapsed > 0) "A pause belongs to the process." else "A little further starts here." else if (elapsed < 15 * 60000) "Your attention is adding up." else "Look at the time you have given yourself.", fontFamily = Serif, fontSize = 27.sp, color = Palette.sage)
            Body("Start with your attention. Choose your subject and completed lectures when you finish.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val enabled = !busy && !remoteBusy && (auth == null || online)
                if (timer == null) Action("Start stopwatch", enabled = enabled, icon = Icons.Outlined.PlayArrow) { action("start") }
                else { Action(if (running) "Pause" else "Resume", Modifier.weight(1f), secondary = true, enabled = enabled, icon = if (running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) { action(if (running) "pause" else "resume") }; Action("Finish & log", Modifier.weight(1f), enabled = enabled) { action("finish") } }
            }
            if (error.isNotBlank()) Body(error, color = Palette.danger)
            Body(if (auth == null) "This stopwatch is saved on this phone." else "One shared timer. Control it from web or Android.")
            HorizontalDivider(color = Palette.border)
            TimerNotificationOption(vm)
        }
    }
}

@Composable fun TimerDial(elapsed: Long, running: Boolean, label: String) {
    val context = LocalContext.current
    val animate = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0 }
    val pulse = if (running && animate) { val transition = rememberInfiniteTransition(label = "quiet timer pulse"); val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathing ring"); value } else 0f
    Box(Modifier.fillMaxWidth().height(290.dp).semantics { contentDescription = "Focus dial, elapsed ${timerClock(elapsed)}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(278.dp)) {
            val center = Offset(size.width / 2, size.height / 2); val radius = size.minDimension * .413f
            val beat = if (running && animate) pulse else 0f
            drawCircle(Palette.sage.copy(alpha = .07f + beat * .1f), radius * (1.08f + beat * .05f), center, style = Stroke(1.dp.toPx()))
            drawCircle(Palette.border, radius, center, style = Stroke(2.dp.toPx()))
            drawArc(Palette.teal, -90f, (elapsed % 3600000) / 3600000f * 360f, false, Offset(center.x-radius,center.y-radius), androidx.compose.ui.geometry.Size(radius*2,radius*2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            repeat(60) { n -> val angle = n * Math.PI / 30 - Math.PI / 2; val inside = radius * if (n % 5 == 0) 1.07f else 1.1f; val outside = radius * 1.15f
                drawLine(if (n % 5 == 0) Palette.sage else Palette.border, Offset(center.x+cos(angle).toFloat()*inside,center.y+sin(angle).toFloat()*inside), Offset(center.x+cos(angle).toFloat()*outside,center.y+sin(angle).toFloat()*outside), 1.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { Label(label); Text(timerClock(elapsed), fontFamily = Serif, fontSize = 45.sp, color = Palette.text); Label("YOUR TIME, WELL SPENT") }
    }
}
