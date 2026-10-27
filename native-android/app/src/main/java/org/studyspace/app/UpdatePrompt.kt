package org.studyspace.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable fun NativeUpdateToast(vm: NativeViewModel, snackbar: SnackbarHostState, canShow: Boolean) {
    val state by vm.updater.state.collectAsStateWithLifecycle()
    var announced by rememberSaveable { mutableIntStateOf(0) }
    val release=state.release
    val scope=rememberCoroutineScope()
    LaunchedEffect(release?.versionCode,canShow) {
        if(release!=null && canShow && announced!=release.versionCode) {
            announced=release.versionCode
            if(snackbar.showSnackbar("Study Tracker ${release.versionName} is ready",actionLabel="Update",withDismissAction=true,duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) scope.launch { vm.updater.updateNow(); if(vm.updater.state.value.verifiedApk==null) vm.repo.messages.emit(vm.updater.state.value.message) }
        }
    }
}
@Composable fun NativeUpdateBanner(vm: NativeViewModel) {
    val state by vm.updater.state.collectAsStateWithLifecycle()
    val release=state.release ?: return
    val scope=rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Body(if(state.progress!=null) "Downloading update · ${state.progress}%" else "Study Tracker ${release.versionName} is available",color=Palette.sage)
            if(state.verifiedApk!=null) Body("Verified update · your study data stays in place")
        }
        Action(if(state.verifiedApk!=null) "Install update" else "Update now",secondary=true,enabled=state.progress==null) {
            scope.launch { vm.updater.updateNow(); if(vm.updater.state.value.verifiedApk==null) vm.repo.messages.emit(vm.updater.state.value.message) }
        }
    }
}
