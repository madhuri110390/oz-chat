package im.vector.app.features.permalink

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.navigation.Navigator
import im.vector.app.test.fakes.FakeActiveSessionHolder
import im.vector.app.test.fakes.FakeSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldBeFalse
import org.junit.Before
import org.junit.Test
import im.vector.app.core.extensions.isIgnored
import im.vector.app.features.createdirect.DirectRoomHelper

class PermalinkHandlerTest {

    private val fakeActiveSessionHolder = FakeActiveSessionHolder()
    private val fakeSession = FakeSession()
    private val userPreferencesProvider = mockk<UserPreferencesProvider>()
    private val navigator = mockk<Navigator>()
    private val directRoomHelper = mockk<DirectRoomHelper>()

    private val permalinkHandler = PermalinkHandler(
        activeSessionHolder = fakeActiveSessionHolder.instance,
        userPreferencesProvider = userPreferencesProvider,
        navigator = navigator,
        directRoomHelper = directRoomHelper
    )

    @Before
    fun setup() {
        mockkStatic("im.vector.app.core.extensions.UriExtensionsKt")
        fakeActiveSessionHolder.givenGetSafeActiveSessionReturns(fakeSession)
        fakeSession.fakeUserId = "@user:openzippers.com"
    }

    @Test
    fun `when deepLink is a promotional link for ozchat, it returns true`() = runTest {
        val fragmentActivity = mockk<FragmentActivity>()
        val deepLinkUri = mockk<Uri>()

        // Mock URI for promotional link
        every { deepLinkUri.host } returns "openzipper.com"
        every { deepLinkUri.path } returns "/ozchat"
        every { deepLinkUri.fragment } returns null
        every { deepLinkUri.getQueryParameter(any()) } returns null
        every { deepLinkUri.isIgnored() } returns false

        val result = permalinkHandler.launch(fragmentActivity, deepLinkUri)
        result.shouldBeTrue()
    }

    @Test
    fun `when deepLink is a promotional link for ozchat app domain, it returns true`() = runTest {
        val fragmentActivity = mockk<FragmentActivity>()
        val deepLinkUri = mockk<Uri>()

        // Mock URI for promotional link
        every { deepLinkUri.host } returns "ozchat.app"
        every { deepLinkUri.path } returns ""
        every { deepLinkUri.fragment } returns null
        every { deepLinkUri.getQueryParameter(any()) } returns null
        every { deepLinkUri.isIgnored() } returns false

        val result = permalinkHandler.launch(fragmentActivity, deepLinkUri)
        result.shouldBeTrue()
    }

    @Test
    fun `when deepLink has no session, custom domains fail`() = runTest {
        fakeActiveSessionHolder.givenGetSafeActiveSessionReturns(null)
        val fragmentActivity = mockk<FragmentActivity>()
        val deepLinkUri = mockk<Uri>()

        every { deepLinkUri.host } returns "ozchat.app"
        every { deepLinkUri.path } returns "/room/!someroom:oz.openzippers.com"
        every { deepLinkUri.fragment } returns null
        every { deepLinkUri.getQueryParameter(any()) } returns null
        every { deepLinkUri.isIgnored() } returns false

        val result = permalinkHandler.launch(fragmentActivity, deepLinkUri)
        result.shouldBeFalse()
    }
}
