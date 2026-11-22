package com.pixora.gallery.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.pixora.gallery.core.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class OcrResult(
    val text: String,
    val meanConfidence: Int,
    val engine: String = "tesseract-rus+eng",
)

class TesseractOcrEngine(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pixora-ocr").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private var api: TessBaseAPI? = null
    private var failed = false

    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(dispatcher) {
        val tess = ensure()
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            tess.setImage(argb)
            val text = tess.getUTF8Text().orEmpty().trim()
            val confidence = tess.meanConfidence().coerceIn(0, 100)
            tess.clear()
            OcrResult(text = text, meanConfidence = confidence)
        } finally {
            if (argb !== bitmap) argb.recycle()
        }
    }

    fun close() {
        api?.recycle()
        api = null
    }

    private fun ensure(): TessBaseAPI {
        api?.let { return it }
        if (failed) error("ocr-init")
        val root = prepareData()
        val tess = TessBaseAPI()
        val ok = tess.init(root.absolutePath, "rus+eng")
        if (!ok) {
            tess.recycle()
            failed = true
            error("ocr-init")
        }
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        tess.setVariable("user_defined_dpi", "300")
        api = tess
        AppLog.i("tesseract ready")
        return tess
    }

    private fun prepareData(): File {
        val root = File(appContext.filesDir, "tesseract")
        val dir = File(root, "tessdata")
        dir.mkdirs()
        listOf("eng", "rus").forEach { lang ->
            val target = File(dir, "$lang.traineddata")
            appContext.assets.open("tessdata/$lang.traineddata").use { input ->
                val bytes = input.readBytes()
                if (!target.exists() || target.length() != bytes.size.toLong()) {
                    target.writeBytes(bytes)
                }
            }
        }
        return root
    }
}
