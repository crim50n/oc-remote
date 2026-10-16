package dev.minios.ocremote

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Navigation only: never edits fields, saves a server, or opens a connection. */
@RunWith(AndroidJUnit4::class)
class PhysicalDeviceSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val sanitizedFailures = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val keyguard = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                assumeFalse("Unlock the device before running physical navigation", keyguard.isKeyguardLocked)
                smokeStep("activity-start-or-finish") { base.evaluate() }
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(sanitizedFailures).around(compose)

    @Test
    fun navigatesSettingsAddServerAndAboutWithoutSaving() {
        smokeStep("home-start") {
            compose.mainClock.autoAdvance = false
            settleNavigation()
            compose.onNodeWithText(label(R.string.home_title)).assertIsDisplayed()
        }
        smokeStep("settings-open") {
            compose.onAllNodesWithContentDescription(label(R.string.settings_title)).onFirst().performClick()
            settleNavigation()
            compose.onNodeWithText(label(R.string.settings_title)).assertIsDisplayed()
        }
        smokeStep("settings-back") {
            compose.onAllNodesWithContentDescription(label(R.string.back)).onFirst().performClick()
            settleNavigation()
            compose.onNodeWithText(label(R.string.home_title)).assertIsDisplayed()
        }
        smokeStep("add-server-open") {
            compose.onAllNodesWithContentDescription(label(R.string.home_add_server)).onFirst().performClick()
            settleNavigation()
            compose.onAllNodesWithText(label(R.string.server_url)).onFirst().assertIsDisplayed()
        }
        smokeStep("add-server-cancel") {
            compose.onAllNodesWithText(label(R.string.cancel)).onFirst().performClick()
            settleNavigation()
            compose.onNodeWithText(label(R.string.home_title)).assertIsDisplayed()
        }
        smokeStep("about-open") {
            compose.onAllNodesWithContentDescription(label(R.string.about_title)).onFirst().performClick()
            settleNavigation()
            compose.onNodeWithText(label(R.string.about_title)).assertIsDisplayed()
            compose.onAllNodesWithText(BuildConfig.VERSION_NAME, substring = true).onFirst().assertIsDisplayed()
        }
        smokeStep("about-back") {
            compose.onAllNodesWithContentDescription(label(R.string.back)).onFirst().performClick()
            settleNavigation()
            compose.onNodeWithText(label(R.string.home_title)).assertIsDisplayed()
        }
    }

    private fun label(resourceId: Int): String = compose.activity.getString(resourceId)

    private fun settleNavigation() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun smokeStep(stage: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: SanitizedSmokeFailure) {
            throw failure
        } catch (_: CancellationException) {
            throw CancellationException("Physical smoke cancelled")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SanitizedSmokeFailure("interrupted")
        } catch (_: AssertionError) {
            throw SanitizedSmokeFailure(stage)
        } catch (_: Exception) {
            throw SanitizedSmokeFailure(stage)
        }
    }

    private class SanitizedSmokeFailure(stage: String) :
        AssertionError("Physical smoke failed at stage: $stage")
}
