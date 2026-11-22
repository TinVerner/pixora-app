package com.pixora.gallery

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.pixora.gallery.background.AnalysisJobs
import com.pixora.gallery.background.WorkScheduler
import com.pixora.gallery.albums.MlKitImageCategorizer
import com.pixora.gallery.classification.HeuristicContentInterpreter
import com.pixora.gallery.data.db.PixoraDatabase
import com.pixora.gallery.data.media.MediaStoreDataSource
import com.pixora.gallery.data.media.PhysicalAlbumGateway
import com.pixora.gallery.data.media.TrashGateway
import com.pixora.gallery.data.notes.NoteRepository
import com.pixora.gallery.data.prefs.SettingsRepository
import com.pixora.gallery.ocr.TesseractOcrEngine
import com.pixora.gallery.ops.BatchOperationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PixoraApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).build()
}

class AppGraph(app: PixoraApp) {
    val appContext = app.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = PixoraDatabase.create(appContext)
    val settings = SettingsRepository(appContext)
    val mediaStore = MediaStoreDataSource(appContext)
    val notes = NoteRepository(db)
    val ocr = TesseractOcrEngine(appContext)
    val interpreter = HeuristicContentInterpreter()
    val categorizer = MlKitImageCategorizer()
    val trash = TrashGateway(appContext.contentResolver)
    val mover = PhysicalAlbumGateway(appContext.contentResolver)
    val coordinator = BatchOperationCoordinator(notes)
    val jobs = AnalysisJobs(db, mediaStore, settings, ocr, interpreter, categorizer, scope)
    val scheduler = WorkScheduler(appContext)

    fun start() {
        scope.launch { notes.dropCategoryLabels() }
        scope.launch {
            settings.flow.collect { current ->
                val locales = when (current.language) {
                    "en" -> LocaleListCompat.forLanguageTags("en")
                    "system" -> LocaleListCompat.getEmptyLocaleList()
                    else -> LocaleListCompat.forLanguageTags("ru")
                }
                if (AppCompatDelegate.getApplicationLocales() != locales) {
                    AppCompatDelegate.setApplicationLocales(locales)
                }
                scheduler.apply(current)
            }
        }
    }
}
