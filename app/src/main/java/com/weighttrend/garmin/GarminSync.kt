package com.weighttrend.garmin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weighttrend.R
import com.weighttrend.WeightTrendApp
import com.weighttrend.core.FitWeightWriter
import com.weighttrend.core.Measurement
import com.weighttrend.data.Repository
import com.weighttrend.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends new weigh-ins (from the scale or typed in) to Garmin Connect.
 * Imported history is never sent automatically.
 */
object GarminSync {
    private const val TAG = "GarminSync"
    private const val WORK = "garmin-upload"
    private val mutex = Mutex()

    sealed interface Outcome {
        data class Sent(val count: Int) : Outcome
        data object NothingToSend : Outcome
        data object NotConnected : Outcome
        data object NeedsLogin : Outcome
        data class NetworkError(val message: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun pending(repo: Repository): List<Measurement> =
        repo.measurements.value.filter {
            !it.garminExported &&
                (it.source == Measurement.Source.SCALE || it.source == Measurement.Source.MANUAL)
        }

    /**
     * Schedules an upload. The default delay leaves time for the scale to send
     * the impedance reading, so a weigh-in goes to Garmin once, with body composition.
     */
    fun schedule(context: Context, delaySeconds: Long = 90) {
        if (!GarminStore(context).isConnected) return
        val req = OneTimeWorkRequestBuilder<Worker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun syncNow(context: Context, repo: Repository): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock { doSync(context.applicationContext, repo) }
    }

    private fun doSync(context: Context, repo: Repository): Outcome {
        val store = GarminStore(context)
        var tokens = store.tokens ?: return Outcome.NotConnected
        if (store.needsLogin) return Outcome.NeedsLogin
        val todo = pending(repo)
        if (todo.isEmpty()) return Outcome.NothingToSend

        try {
            if (System.currentTimeMillis() > tokens.accessExpiresAtMs - 15 * 60_000L) {
                tokens = GarminApi.refresh(tokens).also { store.tokens = it }
            }
            val fit = FitWeightWriter.write(todo)
            val name = "weight_${todo.last().timestampMs / 1000}.fit"
            var result = GarminApi.upload(tokens, name, fit)
            if (result == GarminApi.UploadResult.Unauthorized) {
                tokens = GarminApi.refresh(tokens).also { store.tokens = it }
                result = GarminApi.upload(tokens, name, fit)
            }
            return when (result) {
                GarminApi.UploadResult.Ok, GarminApi.UploadResult.Duplicate -> {
                    repo.markGarminExported(todo.map { it.id })
                    store.lastSuccessMs = System.currentTimeMillis()
                    store.lastStatus = "Отправлено взвешиваний: ${todo.size}"
                    Outcome.Sent(todo.size)
                }
                GarminApi.UploadResult.Unauthorized -> {
                    store.needsLogin = true
                    store.lastStatus = "Garmin не принял вход, войдите заново"
                    Outcome.NeedsLogin
                }
                is GarminApi.UploadResult.Failed -> {
                    store.lastStatus = result.message
                    Outcome.Failed(result.message)
                }
            }
        } catch (e: GarminApi.AuthException) {
            store.needsLogin = true
            store.lastStatus = e.message
            return Outcome.NeedsLogin
        } catch (e: IOException) {
            Log.w(TAG, "network", e)
            store.lastStatus = "Нет связи с Garmin: ${e.message}"
            return Outcome.NetworkError(e.message ?: "")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyLoginNeeded(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, WeightTrendApp.CHANNEL_WEIGH_IN)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Garmin: нужно войти заново")
            .setContentText("Взвешивания сохраняются и уйдут в Garmin после входа")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(9001, n)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val repo = Repository.get(applicationContext)
            return when (syncNow(applicationContext, repo)) {
                is Outcome.NetworkError -> if (runAttemptCount < 5) Result.retry() else Result.failure()
                Outcome.NeedsLogin -> { notifyLoginNeeded(applicationContext); Result.failure() }
                else -> Result.success()
            }
        }
    }
}
