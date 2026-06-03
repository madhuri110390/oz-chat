package im.vector.app.push.fcm

import android.content.Intent
import com.google.firebase.messaging.RemoteMessage
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.pushers.PushParser
import im.vector.app.core.pushers.VectorPushHandler
import im.vector.app.features.mdm.MdmData
import im.vector.app.features.mdm.MdmService
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.features.notifications.NotificationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * Unit tests for [VectorFirebaseMessagingService].
 * Uses Robolectric to instantiate the service and Mockito for dependency mocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // Use a recent SDK version
class VectorFirebaseMessagingServiceTest {

    private lateinit var service: VectorFirebaseMessagingService

    @Mock private lateinit var fcmHelper: FcmHelper
    @Mock private lateinit var vectorPreferences: VectorPreferences
    @Mock private lateinit var activeSessionHolder: ActiveSessionHolder
    @Mock private lateinit var pushersManager: PushersManager
    @Mock private lateinit var pushParser: PushParser
    @Mock private lateinit var vectorPushHandler: VectorPushHandler
    @Mock private lateinit var unifiedPushHelper: UnifiedPushHelper
    @Mock private lateinit var mdmService: MdmService
    @Mock private lateinit var notificationUtils: NotificationUtils
    @Mock private lateinit var screenWakeManager: ScreenWakeManager
    @Mock private lateinit var appScope: CoroutineScope

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        // Create the service via Robolectric
        service = Robolectric.buildService(VectorFirebaseMessagingService::class.java).create().get()
        // Manually inject mocks (fields are lateinit var with @Inject)
        service.fcmHelper = fcmHelper
        service.vectorPreferences = vectorPreferences
        service.activeSessionHolder = activeSessionHolder
        service.pushersManager = pushersManager
        service.pushParser = pushParser
        service.vectorPushHandler = vectorPushHandler
        service.unifiedPushHelper = unifiedPushHelper
        service.mdmService = mdmService
        service.notificationUtils = notificationUtils
        service.screenWakeManager = screenWakeManager
        service.appScope = appScope
        Mockito.`when`(vectorPreferences.areNotificationEnabledForDevice()).thenReturn(true)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun `onNewToken registers pusher when all conditions are met`() = runTest {
        val token = "test-token"
        // Arrange conditions
        Mockito.`when`(vectorPreferences.areNotificationEnabledForDevice()).thenReturn(true)
        Mockito.`when`(activeSessionHolder.hasActiveSession()).thenReturn(true)
        Mockito.`when`(unifiedPushHelper.isEmbeddedDistributor()).thenReturn(true)
        // Mock appScope launch to execute immediately
        val testScope = TestScope()
        service.appScope = testScope
        testScope.testScheduler.advanceUntilIdle()
        // Act
        service.onNewToken(token)
        testScope.testScheduler.advanceUntilIdle()
        // Assert stale pushers are removed and new token is registered
        Mockito.verify(pushersManager).unregisterStalePushers(token)
        Mockito.verify(pushersManager).enqueueRegisterPusherWithFcmKey(token)
    }

    @Test
    fun `onNewToken does not register pusher when preferences disabled`() = runTest {
        val token = "test-token"
        Mockito.`when`(vectorPreferences.areNotificationEnabledForDevice()).thenReturn(false)
        Mockito.`when`(activeSessionHolder.hasActiveSession()).thenReturn(true)
        Mockito.`when`(unifiedPushHelper.isEmbeddedDistributor()).thenReturn(true)
        // Act
        service.onNewToken(token)
        // Verify no interaction with pushersManager
        Mockito.verifyNoInteractions(pushersManager)
    }

    @Test
    fun `onMessageReceived wakes screen and forwards push data`() = runTest {
        val data = mapOf("key" to "value")
        val remoteMessage = RemoteMessage.Builder("test@fcm")
            .setData(data)
            .build()
        val parsed = im.vector.app.core.pushers.PushData(
                unifiedPushData = im.vector.app.core.pushers.UnifiedPushData(
                        eventId = null,
                        roomId = null,
                        unread = null,
                        missedCalls = null,
                ),
                gateway = null,
        )
        Mockito.`when`(pushParser.parsePushDataFcm(data)).thenReturn(parsed)
        Mockito.`when`(notificationUtils.areSystemNotificationsEnabled()).thenReturn(true)
        val testScope = TestScope()
        service.appScope = testScope
        service.onMessageReceived(remoteMessage)
        testScope.testScheduler.advanceUntilIdle()
        Mockito.verify(screenWakeManager).wakeScreenForNotification()
        Mockito.verify(pushParser).parsePushDataFcm(data)
        Mockito.verify(vectorPushHandler).handle(parsed)
    }
}
