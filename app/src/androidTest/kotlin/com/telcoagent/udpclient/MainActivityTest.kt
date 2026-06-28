package com.telcoagent.udpclient

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun appLaunchesSuccessfully() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.telcoagent.udpclient", appContext.packageName)
    }

    @Test
    fun toolbarShowsAppName() {
        onView(allOf(isDescendantOfA(withId(R.id.toolbar)), withText("NetProbe")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun toolbarShowsVersion() {
        onView(withId(R.id.toolbar))
            .check(matches(hasDescendant(withSubstring("v"))))
    }

    @Test
    fun tabLayoutIsDisplayed() {
        onView(withId(R.id.tabLayout))
            .check(matches(isDisplayed()))
    }

    @Test
    fun allTabsExist() {
        // Check all 5 tabs are present
        onView(withText("Around")).check(matches(isDisplayed()))
        onView(withText("Probe")).check(matches(isDisplayed()))
        onView(withText("Drive")).check(matches(isDisplayed()))
        onView(withText("Trace")).check(matches(isDisplayed()))
    }

    @Test
    fun viewPagerIsDisplayed() {
        onView(withId(R.id.viewPager))
            .check(matches(isDisplayed()))
    }
}
