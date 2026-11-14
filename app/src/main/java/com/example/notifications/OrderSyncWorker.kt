package com.example.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.core.network.PizzoraApiException
import com.example.core.security.TokenStorage
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class OrderNotificationSyncer(
  private val notificationRepository: NotificationRepository,
  private val tokenStorage: TokenStorage,
  private val notificationPoster: LocalNotificationPoster,
) {
  suspend fun syncUnreadAndNotify(): ListenableWorker.Result {
    if (tokenStorage.currentSession() == null) {
      return ListenableWorker.Result.success()
    }
    return notificationRepository.syncInbox(unreadOnly = true, limit = 50).fold(
      onSuccess = { outcome ->
        val unreadNew = outcome.newlyInserted.filter { !it.isRead }
        if (unreadNew.isNotEmpty()) {
          notificationPoster.showNotifications(unreadNew)
        }
        ListenableWorker.Result.success()
      },
      onFailure = { err ->
        if (err is CancellationException) throw err
        if (err is PizzoraApiException && err.statusCode in 400..499) {
          ListenableWorker.Result.success()
        } else {
          ListenableWorker.Result.retry()
        }
      },
    )
  }
}

class OrderSyncWorker(
  appContext: Context,
  workerParams: WorkerParameters,
  private val notificationRepository: NotificationRepository,
  private val tokenStorage: TokenStorage,
  private val notificationPoster: LocalNotificationPoster,
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    return try {
      val result = OrderNotificationSyncer(
        notificationRepository = notificationRepository,
        tokenStorage = tokenStorage,
        notificationPoster = notificationPoster,
      ).syncUnreadAndNotify()

      if (result == Result.retry() && runAttemptCount >= MAX_ATTEMPTS) {
        Result.failure()
      } else {
        result
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (t: Throwable) {
      Result.failure(workDataOf("error" to t.stackTraceToString().take(1000)))
    }
  }

  companion object {
    const val PERIODIC_WORK_NAME: String = "pizzora_order_sync_periodic"
    const val EXPEDITED_WORK_NAME: String = "pizzora_order_sync_expedited"
    private const val MAX_ATTEMPTS: Int = 3
  }
}

class PizzoraWorkerFactory(
  private val notificationRepositoryProvider: () -> NotificationRepository,
  private val tokenStorageProvider: () -> TokenStorage,
  private val notificationPosterProvider: () -> LocalNotificationPoster,
) : WorkerFactory() {

  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? {
    val override = testOverride
    if (override != null && override !== this) {
      return override.createWorker(appContext, workerClassName, workerParameters)
    }
    return when (workerClassName) {
      OrderSyncWorker::class.java.name -> OrderSyncWorker(
        appContext = appContext,
        workerParams = workerParameters,
        notificationRepository = notificationRepositoryProvider(),
        tokenStorage = tokenStorageProvider(),
        notificationPoster = notificationPosterProvider(),
      )
      else -> null
    }
  }

  companion object {
    @Volatile
    internal var testOverride: WorkerFactory? = null
  }
}

object OrderSyncScheduler {
  fun schedulePeriodicSync(context: Context) {
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()

    val request = PeriodicWorkRequestBuilder<OrderSyncWorker>(
      repeatInterval = 15,
      repeatIntervalTimeUnit = TimeUnit.MINUTES,
    )
      .setConstraints(constraints)
      .build()

    runCatching {
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        OrderSyncWorker.PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }
  }

  fun triggerExpeditedSync(context: Context) {
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()

    val request = OneTimeWorkRequestBuilder<OrderSyncWorker>()
      .setConstraints(constraints)
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .build()

    runCatching {
      WorkManager.getInstance(context).enqueueUniqueWork(
        OrderSyncWorker.EXPEDITED_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        request,
      )
    }
  }
}
