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

package io.github.inq_o.phonedoctor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import io.github.inq_o.phonedoctor.core.data.test.fakeAlert
import io.github.inq_o.phonedoctor.ui.MainActivity

@HiltAndroidTest
class AppTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun home_showsOpenAlertOrUsageAccessGuide() {
        // 시험 기기에서 사용 기록 보기를 켰으면 확인할 앱 카드가, 안 켰으면 켜 달라는 안내가 보인다
        composeTestRule.waitForIdle()
        val card = composeTestRule.onAllNodes(androidx.compose.ui.test.hasText(fakeAlert.label)).fetchSemanticsNodes().isNotEmpty()
        if (!card) composeTestRule.onNodeWithText("광고 찾기가 꺼져 있어요").assertExists()
    }
}
