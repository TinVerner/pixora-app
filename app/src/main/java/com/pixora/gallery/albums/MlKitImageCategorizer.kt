package com.pixora.gallery.albums

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.pixora.gallery.core.AppLog
import java.util.concurrent.TimeUnit

class MlKitImageCategorizer {
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    }

    fun label(bitmap: Bitmap): List<Pair<String, Float>> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = Tasks.await(labeler.process(image), 25, TimeUnit.SECONDS)
            labels.map { it.text to it.confidence }
        } catch (error: Exception) {
            AppLog.e("image labeling failed", error)
            emptyList()
        }
    }

    fun close() {
        try {
            labeler.close()
        } catch (error: Exception) {
            AppLog.e("labeler close", error)
        }
    }
}
