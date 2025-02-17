package org.owntracks.android.e2e

import android.Manifest
import android.view.View
import android.view.animation.Animation
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import androidx.test.espresso.IdlingResource.ResourceCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.adevinta.android.barista.assertion.BaristaRecyclerViewAssertions.assertRecyclerViewItemCount
import com.adevinta.android.barista.assertion.BaristaVisibilityAssertions.assertContains
import com.adevinta.android.barista.assertion.BaristaVisibilityAssertions.assertDisplayed
import com.adevinta.android.barista.assertion.BaristaVisibilityAssertions.assertNotDisplayed
import com.adevinta.android.barista.interaction.BaristaClickInteractions.clickBack
import com.adevinta.android.barista.interaction.BaristaDialogInteractions.clickDialogPositiveButton
import com.adevinta.android.barista.interaction.BaristaDrawerInteractions.openDrawer
import com.adevinta.android.barista.interaction.BaristaEditTextInteractions.writeTo
import com.adevinta.android.barista.interaction.PermissionGranter
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.owntracks.android.R
import org.owntracks.android.testutils.TestWithAnActivity
import org.owntracks.android.testutils.setNotFirstStartPreferences
import org.owntracks.android.ui.clickOnAndWait
import org.owntracks.android.ui.map.MapActivity
import java.util.concurrent.TimeUnit


@LargeTest
@RunWith(AndroidJUnit4::class)
class ContactActivityTests : TestWithAnActivity<MapActivity>(MapActivity::class.java) {

    private var mockWebServer = MockWebServer()

    @Before
    fun setIdlingTimeout() {
        IdlingPolicies.setIdlingResourceTimeout(30, TimeUnit.SECONDS)
    }

    @Before
    fun startMockWebserver() {
        try {
            mockWebServer.start()
        } catch (e: IllegalArgumentException) {
            // Already started
        }
        mockWebServer.dispatcher = MockWebserverLocationDispatcher(locationResponse)
    }

    @After
    fun stopMockWebserver() {
        mockWebServer.shutdown()
    }

    @After
    fun unregisterIdlingResource() {
        try {
            IdlingRegistry.getInstance()
                .unregister(activity.locationIdlingResource)
        } catch (_: NullPointerException) {
            // Happens when the vm is already gone from the MapActivity
        }
        try {
            IdlingRegistry.getInstance()
                .unregister(activity.outgoingQueueIdlingResource)
        } catch (_: NullPointerException) {
        }
    }

    private val locationResponse = """
        {"_type":"location","acc":20,"al":0,"batt":100,"bs":0,"conn":"w","created_at":1610748273,"lat":51.2,"lon":-4,"tid":"aa","tst":1610799026,"vac":40,"vel":7}
    """.trimIndent()

    @Test
    fun testClickingOnContactLoadsContactOnMap() {
        setNotFirstStartPreferences()
        launchActivity()

        val httpPort = mockWebServer.port
        PermissionGranter.allowPermissionsIfNeeded(Manifest.permission.ACCESS_FINE_LOCATION)

        openDrawer()
        clickOnAndWait(R.string.title_activity_preferences)
        clickOnAndWait(R.string.preferencesServer)
        clickOnAndWait(R.string.mode_heading)
        clickOnAndWait(R.string.mode_http_private_label)
        clickDialogPositiveButton()
        clickOnAndWait(R.string.preferencesHost)
        writeTo(R.id.url, "http://localhost:${httpPort}/")
        clickDialogPositiveButton()
        clickBack()

        openDrawer()
        clickOnAndWait(R.string.title_activity_map)

        val locationIdlingResource = activity.locationIdlingResource
        IdlingRegistry.getInstance().register(locationIdlingResource)

        clickOnAndWait(R.id.menu_report)

        val outgoingQueueIdlingResource = activity.outgoingQueueIdlingResource
        IdlingRegistry.getInstance().register(outgoingQueueIdlingResource)

        openDrawer()
        clickOnAndWait(R.string.title_activity_contacts)
        assertRecyclerViewItemCount(R.id.recycler_view, 1)

        clickOnAndWait("aa")
        assertDisplayed(R.id.bottomSheetLayout)
        assertDisplayed(R.id.contactPeek)
        assertContains(R.id.name, "aa")

        clickOnAndWait(R.id.menu_mylocation)

        assertNotDisplayed(R.id.bottomSheetLayout)
        assertNotDisplayed(R.id.contactPeek)
    }

    class MockWebserverLocationDispatcher(private val config: String) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val errorResponse = MockResponse().setResponseCode(404)
            return if (request.path == "/") {
                MockResponse().setResponseCode(200).setHeader("Content-type", "application/json")
                    .setBody(config)
            } else {
                errorResponse
            }
        }
    }

    class AnimationIdlingResource(view: View) : IdlingResource {
        private var callback: ResourceCallback? = null
        override fun getName(): String {
            return AnimationIdlingResource::class.java.name
        }

        override fun isIdleNow(): Boolean {
            return true
        }

        override fun registerIdleTransitionCallback(callback: ResourceCallback) {
            this.callback = callback
        }

        init {
            if (view.animation == null) {
                callback!!.onTransitionToIdle()
            } else {
                view.animation.setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(animation: Animation) {}
                    override fun onAnimationEnd(animation: Animation) {
                        callback!!.onTransitionToIdle()
                    }

                    override fun onAnimationRepeat(animation: Animation) {}
                })
            }
        }
    }
}