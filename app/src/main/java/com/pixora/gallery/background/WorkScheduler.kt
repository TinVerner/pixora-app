package com.pixora.gallery.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pixora.gallery.data.prefs.UserSettings
import java.util.concurrent.TimeUnit

class WorkScheduler(private val context: Context) {
    fun apply(settings: UserSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.backgroundEnabled) {
            manager.cancelUniqueWork(NAME)
            return
        }
        val constraints = Constraints.Builder().apply {
            if (settings.onlyCharging) setRequiresCharging(true)
            if (settings.batteryNotLow) setRequiresBatteryNotLow(true)
        }.build()
        val hours = settings.intervalHours.coerceIn(6, 168).toLong()
        val request = PeriodicWorkRequestBuilder<GalleryWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val NAME = "pixora-periodic"
    }
}
