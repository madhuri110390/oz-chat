package im.vector.app.push.fcm



import android.content.Context
import android.os.PowerManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenWakeManager @Inject constructor(
        private val context: Context,
) {
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    fun wakeScreenForNotification() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        try {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OZChat::CpuWake")
                    .apply {
                        setReferenceCounted(false)
                        acquire(20_000L)
                    }
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire CPU WakeLock")
        }

        try {
            if (!pm.isInteractive) {
                screenWakeLock?.let { if (it.isHeld) it.release() }
                @Suppress("DEPRECATION")
                screenWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                PowerManager.ON_AFTER_RELEASE,
                        "OZChat::ScreenWake"
                ).apply {
                    setReferenceCounted(false)
                    acquire(10_000L)
                }
                Timber.d("Screen woken for notification")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire screen WakeLock")
        }
    }

    fun releaseCpuWake() {
        try {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to release CPU WakeLock")
        }
    }
}
