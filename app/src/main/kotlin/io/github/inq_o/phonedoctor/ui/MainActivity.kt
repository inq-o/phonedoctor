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
        if (savedInstanceState == null) handleAlertIntent(intent)
        setContent {
            PhoneDoctorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
                    val pendingAlertId by viewModel.pendingAlertId.collectAsStateWithLifecycle()
                    // 설정을 읽기 전에는 아무 화면도 그리지 않는다 (첫 설정 화면이 잠깐 보였다 사라지지 않게)
                    onboardingDone?.let { done ->
                        MainNavigation(
                            onboardingDone = done,
                            pendingAlertId = pendingAlertId,
                            onAlertOpened = viewModel::consumePendingAlert,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAlertIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppOpened()
    }

    /** 알림을 눌러 들어왔으면 그 앱 상세 화면으로 간다 */
    private fun handleAlertIntent(intent: Intent?) {
        val id = intent?.getLongExtra(SystemNotifier.EXTRA_ALERT_ID, -1L) ?: -1L
        if (id > 0) viewModel.openAlert(id)
    }
}
