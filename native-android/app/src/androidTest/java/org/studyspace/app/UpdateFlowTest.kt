package org.studyspace.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun availableReleaseShowsUpdateActionOutsideSettings() {
        val vm=ViewModelProvider(compose.activity)[NativeViewModel::class.java]
        compose.waitUntil(20000) { vm.repo.ready.value }
        val release=ReleaseManifest(BuildConfig.VERSION_CODE+1,"test-next","a".repeat(64),"https://github.com/soumik15630m/gate-tracker/releases/download/android-latest/studyspace-android.apk")
        compose.runOnIdle { vm.screen.value=Screen.TODAY;vm.updater.state.value=AppUpdateState(release=release) }
        compose.waitUntil(20000) { compose.onAllNodesWithText("Study Tracker test-next is ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Study Tracker test-next is ready").assertExists()
        compose.onNodeWithText("Update now").assertExists()
        compose.onNodeWithText("Update").assertExists()
        compose.runOnIdle { vm.updater.state.value=AppUpdateState() }
    }
}
