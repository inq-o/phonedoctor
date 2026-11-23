/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.inq_o.phonedoctor.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.notifications.SystemNotifier

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        // 화면 회전 등으로 다시 만들어지면 같은 시작 인텐트가 다시 온다: 이미 연 알림이면 건너뛴다.
        // 프로세스가 죽은 뒤 새 알림을 눌러 복원된 경우는 주소가 달라 연다
        val launchUri = intent?.dataString
        if (savedInstanceState == null || (launchUri != null && launchUri != savedInstanceState.getString(KEY_LAUNCH_NOTIFICATION))) {
            handleNotificationIntent(intent)
        }
        setContent {
            PhoneDoctorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val start by viewModel.start.collectAsStateWithLifecycle()
                    val pendingOpen by viewModel.pendingOpen.collectAsStateWithLifecycle()
                    // 설정을 읽기 전에는 아무 화면도 그리지 않는다 (첫 설정 화면이 잠깐 보였다 사라지지 않게)
                    start?.let {
                        MainNavigation(
                            start = it,
                            familyAvailable = viewModel.familyAvailable,
                            guardianSignedIn = { viewModel.guardianSignedIn },
                            pendingOpen = pendingOpen,
                            onOpened = viewModel::consumePendingOpen,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppOpened()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LAUNCH_NOTIFICATION, intent?.dataString)
    }

    /** 알림을 눌러 들어왔으면 그 알림의 화면으로 간다 */
    private fun handleNotificationIntent(intent: Intent?) {
        intent ?: return
        val alertId = intent.getLongExtra(SystemNotifier.EXTRA_ALERT_ID, -1L)
        val linkId = intent.getStringExtra(SystemNotifier.EXTRA_LINK_ID)
        val requestId = intent.getStringExtra(SystemNotifier.EXTRA_REQUEST_ID)
        when {
            alertId > 0 -> viewModel.open(PendingOpen.Alert(alertId))
            !requestId.isNullOrBlank() -> viewModel.open(PendingOpen.GuardianRequest(requestId))
            !linkId.isNullOrBlank() -> viewModel.open(PendingOpen.LinkRequest(linkId))
            intent.getBooleanExtra(SystemNotifier.EXTRA_OPEN_FAMILY, false) -> viewModel.open(PendingOpen.FamilyLinks)
        }
    }

    private companion object {
        const val KEY_LAUNCH_NOTIFICATION = "launchNotification"
    }
}
