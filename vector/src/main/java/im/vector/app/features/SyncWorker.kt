package im.vector.app.features.workers.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.sync.SyncState
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val activeSessionHolder: ActiveSessionHolder
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Limit retries
        if (runAttemptCount >= 3) return Result.failure()

        return withContext(Dispatchers.IO) {
            try {
                val session = activeSessionHolder.getSafeActiveSession()
                        ?: return@withContext Result.failure()

                val syncService = session.syncService()

                when (syncService.getSyncState()) {
                    is SyncState.Running -> {
                        Timber.d("SyncWorker: already running")
                        Result.success()
                    }
                    is SyncState.Idle,
                    is SyncState.Paused -> {
                        syncService.startSync(true)
                        Timber.d("SyncWorker: sync started")
                        showNotification(session.myUserId)
                        Result.success()
                    }
                    is SyncState.InvalidToken -> {
                        Timber.e("SyncWorker: invalid token, aborting")
                        Result.failure()
                    }
                    else -> Result.retry()
                }
            } catch (e: Exception) {
                Timber.e(e, "SyncWorker: exception")
                if (isNetworkError(e)) Result.retry() else Result.failure()
            }
        }
    }

    private fun showNotification(userId: String) {
        val channelId = "sync_channel"
        val context = applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(
                            NotificationChannel(
                                    channelId,
                                    "Sync Notifications",
                                    NotificationManager.IMPORTANCE_DEFAULT
                            ).apply {
                                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                            }
                    )
        }

        NotificationManagerCompat.from(context).notify(
                userId.hashCode(),
                NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Messages synced")
                        .setContentText("Tap to open")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setAutoCancel(true)
                        .build()
        )
    }

    private fun isNetworkError(e: Exception) =
            e is java.net.UnknownHostException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.io.IOException

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                    "force_sync",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                            .setBackoffCriteria(
                                    BackoffPolicy.EXPONENTIAL,
                                    30, TimeUnit.SECONDS
                            )
                            .setConstraints(
                                    Constraints.Builder()
                                            .setRequiredNetworkType(NetworkType.CONNECTED)
                                            .build()
                            )
                            .build()
            )
        }
    }
}
