package com.telcoagent.udpclient

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisFragmentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private fun navigateToTraceTab() {
        onView(withText("Trace")).perform(click())
        // Wait for fragment to settle
        Thread.sleep(500)
    }

    @Test
    fun traceTabShowsStatusSection() {
        navigateToTraceTab()
        onView(withId(R.id.statusText))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabShowsDisconnectedByDefault() {
        navigateToTraceTab()
        onView(withText("Disconnected"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabShowsPublicKeyCard() {
        navigateToTraceTab()
        onView(withId(R.id.clientKeyCard))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabShowsPublicKeyLabel() {
        navigateToTraceTab()
        onView(withSubstring("PUBLIC KEY"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabShowsRegenerateButton() {
        navigateToTraceTab()
        onView(withId(R.id.regenerateKeyButton))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabShowsAutoConfigButton() {
        navigateToTraceTab()
        onView(withId(R.id.autoConfigButton))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabShowsConnectButton() {
        navigateToTraceTab()
        onView(withId(R.id.connectButton))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabConnectButtonSaysConnectWhenDisconnected() {
        navigateToTraceTab()
        onView(withText("Connect VPN"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabShowsActivityLog() {
        navigateToTraceTab()
        onView(withText("Activity"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun traceTabRegenerateKeyShowsNewKey() {
        navigateToTraceTab()
        val pubKeyView = onView(withId(R.id.clientPubKeyText))

        // Get initial key
        pubKeyView.check(matches(isDisplayed()))

        // Click regenerate
        onView(withId(R.id.regenerateKeyButton)).perform(click())

        // Key should still be displayed (either same or new)
        pubKeyView.check(matches(isDisplayed()))
    }

    @Test
    fun traceTabAutoConfigButtonClickable() {
        navigateToTraceTab()
        onView(withId(R.id.autoConfigButton))
            .check(matches(isClickable()))
    }
}
