package io.github.inq_o.phonedoctor.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class NavigationTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun home_opensScanHistory_andReturns() {
        composeTestRule.onNodeWithText("검사 기록 보기").performClick()
        composeTestRule.onNodeWithText("살펴본 기록").assertExists()
        composeTestRule.onNodeWithText("뒤로").performClick()
        composeTestRule.onNodeWithText("검사 기록 보기").assertExists()
    }
}
