package com.flash.headway.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.flash.headway.widget.Widgets
import java.util.concurrent.TimeUnit

/**
 * Downloads the advisory feed every few hours while online, app open or not, so the widget and
 * the countdown pick up a holiday timetable or extended hours without the app being opened.
 * (While the app is open, its own refresh runs every half hour.)
 */
class FeedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val before = Advisories.cached(applicationContext)
        val fresh = Advisories.refresh(applicationContext) ?: return Result.retry()
        if (fresh != before) Widgets.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FeedWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("advisories", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
