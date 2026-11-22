package com.pixora.gallery.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pixora.gallery.PixoraApp
import com.pixora.gallery.R
import com.pixora.gallery.core.AppLog
import kotlinx.coroutines.CancellationException

class GalleryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PixoraApp).graph
        val settings = graph.settings.current()
        if (!settings.backgroundEnabled) return Result.success()
        if (!graph.jobs.tryEnter()) return Result.success()
        return try {
            graph.jobs.indexLibrary()
            graph.jobs.recognizeScreenshots(settings.maxFilesPerRun)
            graph.jobs.hashDuplicates(settings.maxFilesPerRun)
            graph.jobs.buildVisualFeatures(settings.maxFilesPerRun, settings.autoClassify)
            graph.jobs.refreshSuggestions()
            notifyDone()
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            AppLog.e("background scan failed", error)
            Result.retry()
        } finally {
            graph.jobs.leave()
        }
    }

    private fun notifyDone() {
        if (applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, applicationContext.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(applicationContext.getString(R.string.notify_title))
            .setContentText(applicationContext.getString(R.string.notify_body))
            .setAutoCancel(true)
            .build()
        manager.notify(42, notification)
    }

    companion object {
        const val CHANNEL = "pixora"
    }
}
