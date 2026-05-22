package im.vector.app.features.presence

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import timber.log.Timber
import im.vector.app.core.di.ActiveSessionHolder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimplePresenceHandler @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder
) {

    fun setupLifecycleCallback() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        Timber.d("App in foreground")
                        handleAppForeground()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        Timber.d("App in background")
                        handleAppBackground()
                    }
                    else -> { /* Ignore */ }
                }
            }
        })
    }

    private fun handleAppForeground() {
        try {
            activeSessionHolder.getSafeActiveSession()?.let { session ->
                session.syncService().startSync(true)
                Timber.d("Sync started - user online")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in foreground handling")
        }
    }

    private fun handleAppBackground() {
        try {
            Timber.d("App in background - server will mark offline after timeout")
        } catch (e: Exception) {
            Timber.e(e, "Error in background handling")
        }
    }
}
