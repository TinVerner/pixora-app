package com.pixora.gallery.background

import android.graphics.Bitmap
import android.net.Uri
import com.pixora.gallery.albums.LabelMapper
import com.pixora.gallery.albums.MlKitImageCategorizer
import com.pixora.gallery.albums.Signature
import com.pixora.gallery.albums.VisualSignature
import com.pixora.gallery.classification.ContentInterpreter
import com.pixora.gallery.cleanup.CleanupAdvisor
import com.pixora.gallery.cleanup.PhotoFact
import com.pixora.gallery.core.AppLog
import com.pixora.gallery.core.formatIds
import com.pixora.gallery.data.db.AnalysisEntity
import com.pixora.gallery.data.db.MediaItemEntity
import com.pixora.gallery.data.db.OcrCacheEntity
import com.pixora.gallery.data.db.PixoraDatabase
import com.pixora.gallery.data.db.SimilarGroupEntity
import com.pixora.gallery.data.db.SuggestionEntity
import com.pixora.gallery.data.media.MediaRecord
import com.pixora.gallery.data.media.MediaStoreDataSource
import com.pixora.gallery.data.prefs.SettingsRepository
import com.pixora.gallery.duplicates.DHash
import com.pixora.gallery.duplicates.DuplicateFinder
import com.pixora.gallery.duplicates.HashedFile
import com.pixora.gallery.duplicates.QualityScorer
import com.pixora.gallery.duplicates.SimilarClusterer
import com.pixora.gallery.duplicates.VisualItem
import com.pixora.gallery.media.ShotTriage
import com.pixora.gallery.notes.NoteDeduplicator
import com.pixora.gallery.ocr.TesseractOcrEngine
import androidx.room.withTransaction
import com.pixora.gallery.albums.AlbumLabels
import com.pixora.gallery.core.parseIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class JobState(
    val name: String = "",
    val running: Boolean = false,
    val paused: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
)

class AnalysisJobs(
    private val db: PixoraDatabase,
    private val mediaStore: MediaStoreDataSource,
    private val settings: SettingsRepository,
    private val ocr: TesseractOcrEngine,
    private val interpreter: ContentInterpreter,
    private val categorizer: MlKitImageCategorizer,
    private val scope: CoroutineScope,
) {
    private val json = Json { encodeDefaults = true }
    private val gate = Mutex()
    private val pause = AtomicBoolean(false)
    private var job: Job? = null
    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state

    fun running(): Boolean = job?.isActive == true

    fun pause() {
        pause.set(true)
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        pause.set(false)
        _state.update { it.copy(paused = false) }
    }

    fun cancel() {
        pause.set(false)
        job?.cancel()
    }

    suspend fun tryEnter(): Boolean = gate.tryLock()

    fun leave() {
        gate.unlock()
    }

    fun launch(name: String, block: suspend () -> Unit) {
        if (job?.isActive == true) {
            AppLog.i("launch $name skipped, another job is still active")
            return
        }
        AppLog.i("launch $name")
        pause.set(false)
        job = scope.launch {
            AppLog.i("job $name body")
            _state.value = JobState(name = name, running = true)
            try {
                gate.withLock {
                    AppLog.i("job $name locked")
                    block()
                }
                AppLog.i("job $name finished")
                _state.update { it.copy(running = false, paused = false, message = null) }
            } catch (cancel: CancellationException) {
                AppLog.e("job $name cancelled", cancel)
                _state.update { it.copy(running = false, paused = false, message = null) }
                throw cancel
            } catch (error: Exception) {
                AppLog.e("job $name failed", error)
                _state.update { it.copy(running = false, paused = false, message = error.message ?: "error") }
            }
        }
    }

    suspend fun indexLibrary() {
        val records = withContext(Dispatchers.IO) { mediaStore.loadAll() }
        val existing = db.media().getAll().associateBy { it.id }
        val entities = records.map { record ->
            val previous = existing[record.id]
            val same = previous != null &&
                previous.dateModified == record.dateModified &&
                previous.sizeBytes == record.sizeBytes
            record.toEntity(previous, same)
        }
        val seen = entities.map { it.id }.toSet()
        val missing = existing.keys.filter { it !in seen }
        db.withTransactionChunked(entities, missing)
        progress(entities.size, entities.size)
    }

    suspend fun recognizeScreenshots(limit: Int? = null, includeNoise: Boolean = false) {
        val shots = db.media().getAll().filter { it.isScreenshot && !it.isVideo }
        val pending = ArrayList<MediaItemEntity>()
        for (shot in shots) {
            if (!includeNoise && !ShotTriage.origin(shot.displayName).worthReading) continue
            val cache = db.ocr().get(shot.id)
            val fresh = cache != null && cache.dateModified == shot.dateModified && cache.sizeBytes == shot.sizeBytes
            if (!fresh) pending += shot
        }
        val ordered = pending.sortedWith(
            compareBy<MediaItemEntity> { ShotTriage.origin(it.displayName).ordinal }
                .thenByDescending { it.dateModified },
        )
        val queue = if (limit == null) ordered else ordered.take(limit)
        AppLog.i("ocr queue ${queue.size} of ${shots.size} screenshots")
        queue.forEachIndexed { index, shot ->
            checkpoint()
            progress(index, queue.size)
            val bitmap = withContext(Dispatchers.IO) {
                mediaStore.decodeSampled(Uri.parse(shot.uri), 2400)
            } ?: return@forEachIndexed
            try {
                val result = ocr.recognize(bitmap)
                val reading = interpreter.interpret(result.text)
                val now = System.currentTimeMillis()
                db.ocr().upsert(
                    OcrCacheEntity(
                        mediaId = shot.id,
                        dateModified = shot.dateModified,
                        sizeBytes = shot.sizeBytes,
                        text = result.text,
                        meanConfidence = result.meanConfidence,
                        engine = result.engine,
                        processedAt = now,
                    ),
                )
                val previous = db.analyses().get(shot.id)
                val entity = if (previous?.userEdited == true) {
                    previous.copy(
                        fullText = result.text,
                        ocrConfidence = result.meanConfidence,
                        updatedAt = now,
                    )
                } else {
                    AnalysisEntity(
                        mediaId = shot.id,
                        category = reading.category.name,
                        title = reading.title,
                        year = reading.year,
                        fieldsJson = json.encodeToString(reading.fields),
                        candidatesJson = json.encodeToString(reading.candidates),
                        confidence = reading.confidence,
                        explainCode = reading.explain.name,
                        fullText = result.text,
                        ocrConfidence = result.meanConfidence,
                        status = "READY",
                        userTitle = reading.title.orEmpty(),
                        userCategory = reading.category.name,
                        userYear = reading.year,
                        userEdited = false,
                        saveChecked = defaultSave(
                            reading.category.watchable,
                            reading.title,
                            result.text,
                            reading.confidence,
                        ) && ShotTriage.origin(shot.displayName).worthReading,
                        trashChecked = false,
                        allowDuplicate = false,
                        updatedAt = now,
                    )
                }
                db.analyses().upsert(entity)
            } finally {
                bitmap.recycle()
            }
        }
        progress(queue.size, queue.size)
    }

    suspend fun hashDuplicates(limit: Int? = null) {
        val items = db.media().getAll()
        val bySize = items.groupBy { it.sizeBytes to it.isVideo }
        val suspects = bySize.values.filter { it.size > 1 }.flatten()
            .filter { it.sha256.isNullOrBlank() }
        val queue = if (limit == null) suspects else suspects.take(limit)
        queue.forEachIndexed { index, item ->
            checkpoint()
            progress(index, queue.size)
            val hash = withContext(Dispatchers.IO) { mediaStore.sha256(Uri.parse(item.uri)) } ?: return@forEachIndexed
            val fresh = db.media().get(item.id) ?: return@forEachIndexed
            db.media().upsertAll(listOf(fresh.copy(sha256 = hash)))
        }
        settings.update { it.copy(duplicateScanAt = System.currentTimeMillis()) }
        progress(queue.size, queue.size)
    }

    suspend fun buildVisualFeatures(limit: Int? = null, label: Boolean) {
        val images = db.media().getAll().filter { !it.isVideo }
        val pending = images.filter { image ->
            image.dHash == null || image.histogram.isNullOrBlank() || (label && image.rawLabel == null && !image.isScreenshot)
        }
        val queue = if (limit == null) pending else pending.take(limit)
        queue.forEachIndexed { index, image ->
            checkpoint()
            progress(index, queue.size)
            val bitmap = withContext(Dispatchers.IO) { mediaStore.thumbnail(Uri.parse(image.uri), 96) } ?: return@forEachIndexed
            try {
                val features = featuresOf(bitmap, image.sizeBytes)
                val labels = if (label && !image.isScreenshot) categorizer.label(bitmap) else emptyList()
                val mapped = LabelMapper.parse(image.labels).toMutableSet()
                if (image.isScreenshot) mapped += AlbumLabels.SCREENSHOTS
                mapped += LabelMapper.map(labels)
                val top = labels.maxByOrNull { it.second }
                val fresh = db.media().get(image.id) ?: image
                db.media().upsertAll(
                    listOf(
                        fresh.copy(
                            dHash = features.dHash,
                            sharpness = features.sharpness,
                            exposureScore = features.exposure,
                            qualityScore = features.quality,
                            histogram = VisualSignature.encode(features.histogram),
                            labels = LabelMapper.format(mapped),
                            rawLabel = top?.first ?: fresh.rawLabel,
                            rawLabelScore = top?.second ?: fresh.rawLabelScore,
                        ),
                    ),
                )
            } finally {
                bitmap.recycle()
            }
        }
        if (label) settings.update { it.copy(labelScanAt = System.currentTimeMillis()) }
        clusterSimilar()
        settings.update { it.copy(similarScanAt = System.currentTimeMillis()) }
        refreshSuggestions()
    }

    suspend fun matchPersonal(albumId: Long, threshold: Float) {
        val exemplars = db.albums().exemplars(albumId)
        if (exemplars.isEmpty()) return
        val signatures = exemplars.map { Signature(it.dHash, VisualSignature.decode(it.histogram)) }
        val existing = db.albums().items(albumId).associateBy { it.mediaId }
        val images = db.media().getAll().filter { !it.isVideo && it.dHash != null && !it.histogram.isNullOrBlank() }
        db.albums().clearSuggestions(albumId)
        val now = System.currentTimeMillis()
        images.forEach { image ->
            if (exemplars.any { it.mediaId == image.id }) return@forEach
            val state = existing[image.id]?.state
            if (state == "REJECTED" || state == "ACCEPTED") return@forEach
            val score = VisualSignature.best(
                signatures,
                Signature(image.dHash ?: return@forEach, VisualSignature.decode(image.histogram)),
            )
            if (score >= threshold) {
                db.albums().upsertItem(
                    com.pixora.gallery.data.db.AlbumItemEntity(
                        albumId = albumId,
                        mediaId = image.id,
                        similarity = score,
                        state = "SUGGESTED",
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun refreshSuggestions() {
        val settingsNow = settings.current()
        val photos = db.media().getAll()
        val excluded = db.exclusions().all().mapNotNull { row ->
            row.key.removePrefix("media:").toLongOrNull()
        }.toSet()
        val exact = DuplicateFinder.group(
            photos.filter { !it.sha256.isNullOrBlank() }.map {
                HashedFile(it.id, it.sha256!!, it.sizeBytes, it.isFavorite, it.id in excluded)
            },
        )
        val similar = readSimilar()
        val saved = db.notes().sourceMediaIds().toSet()
        val facts = photos.filter { !it.isVideo }.map {
            PhotoFact(
                id = it.id,
                sizeBytes = it.sizeBytes,
                sharpness = it.sharpness,
                screenshot = it.isScreenshot,
                favorite = it.isFavorite,
                bucket = it.bucketName,
                dateModifiedMillis = it.dateModified * 1000L,
            )
        }
        val suggestions = CleanupAdvisor.advise(
            photos = facts,
            exact = exact,
            similar = similar,
            savedMediaIds = saved,
            excludedIds = excluded,
            protectedBuckets = settingsNow.excludedBuckets,
            nowMillis = System.currentTimeMillis(),
            oldScreenshotDays = settingsNow.oldScreenshotDays,
        )
        db.suggestions().clear()
        db.suggestions().upsertAll(
            suggestions.map {
                SuggestionEntity(
                    id = it.id,
                    reason = it.reason.name,
                    mediaIds = formatIds(it.mediaIds),
                    keepIds = formatIds(it.keepIds),
                    reclaimBytes = it.reclaimBytes,
                    createdAt = System.currentTimeMillis(),
                )
            },
        )
    }

    private suspend fun clusterSimilar() {
        val prefs = settings.current()
        val images = db.media().getAll().filter { !it.isVideo && it.dHash != null }
        val visuals = images.map {
            VisualItem(
                id = it.id,
                hash = it.dHash ?: 0L,
                timeMillis = it.dateModified * 1000L,
                width = it.width,
                height = it.height,
                sizeBytes = it.sizeBytes,
                quality = it.qualityScore ?: 0.0,
                sharpness = it.sharpness ?: 0.0,
            )
        }
        val burst = 3L * 60L * 1000L
        val pairs = SimilarClusterer.candidatePairs(visuals, burst)
        val groups = SimilarClusterer.cluster(
            items = visuals,
            pairs = pairs,
            threshold = prefs.similarityThreshold,
            strict = 6,
            burstMillis = burst,
        )
        db.suggestions().clearGroups()
        db.suggestions().upsertGroups(
            groups.mapIndexed { index, group ->
                SimilarGroupEntity(
                    groupId = "g$index-${group.ids.minOrNull()}",
                    mediaIds = formatIds(group.ids),
                    keepIds = formatIds(group.keepIds),
                    scores = group.scores.entries.joinToString(";") { "${it.key}=${"%.3f".format(it.value)}" },
                )
            },
        )
    }

    private suspend fun readSimilar(): List<com.pixora.gallery.duplicates.SimilarGroup> =
        db.suggestions().groups().map { row ->
            val scores = row.scores.split(';').mapNotNull { part ->
                val bits = part.split('=')
                val id = bits.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                id to (bits.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
            }.toMap()
            com.pixora.gallery.duplicates.SimilarGroup(
                ids = parseIds(row.mediaIds),
                keepIds = parseIds(row.keepIds),
                scores = scores,
            )
        }

    private suspend fun checkpoint() {
        while (pause.get()) {
            yield()
            kotlinx.coroutines.delay(160)
        }
        yield()
    }

    private fun progress(done: Int, total: Int) {
        _state.update { it.copy(done = done, total = total, running = true) }
    }

    private suspend fun PixoraDatabase.withTransactionChunked(entities: List<MediaItemEntity>, missing: List<Long>) {
        withTransaction {
            entities.chunked(300).forEach { media().upsertAll(it) }
            missing.chunked(300).forEach { media().deleteIds(it) }
        }
    }

    private fun defaultSave(watchable: Boolean, title: String?, text: String, confidence: Int): Boolean {
        if (text.isBlank()) return false
        if (watchable && (title.isNullOrBlank() || NoteDeduplicator.isCategoryLabel(title))) return false
        return text.length >= 8 || !title.isNullOrBlank()
    }

    private data class Features(
        val dHash: Long,
        val histogram: FloatArray,
        val sharpness: Double,
        val exposure: Double,
        val quality: Double,
    )

    private fun MediaRecord.toEntity(previous: MediaItemEntity?, same: Boolean): MediaItemEntity {
        val labels = LabelMapper.parse(if (same) previous?.labels else null).toMutableSet()
        if (isScreenshot) labels += AlbumLabels.SCREENSHOTS else labels -= AlbumLabels.SCREENSHOTS
        return MediaItemEntity(
            id = id,
            uri = uri,
            displayName = displayName,
            relativePath = relativePath,
            bucketName = bucketName,
            mimeType = mimeType,
            dateAdded = dateAdded,
            dateModified = dateModified,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            isScreenshot = isScreenshot,
            isFavorite = isFavorite,
            isVideo = isVideo,
            sha256 = if (same) previous?.sha256 else null,
            dHash = if (same) previous?.dHash else null,
            sharpness = if (same) previous?.sharpness else null,
            exposureScore = if (same) previous?.exposureScore else null,
            qualityScore = if (same) previous?.qualityScore else null,
            histogram = if (same) previous?.histogram else null,
            labels = LabelMapper.format(labels),
            rawLabel = if (same) previous?.rawLabel else null,
            rawLabelScore = if (same) previous?.rawLabelScore else null,
        )
    }

    private fun featuresOf(bitmap: Bitmap, sizeBytes: Long): Features {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val hash = if (width > 1 && height > 1) DHash.compute(pixels, width, height) else 0L
        val hist = VisualSignature.histogram(pixels, width, height)
        val luma = Array(height) { y ->
            DoubleArray(width) { x -> DHash.luminance(pixels[y * width + x]) }
        }
        val sharp = QualityScorer.laplacianVariance(luma)
        var sum = 0.0
        var clip = 0
        pixels.forEach { pixel ->
            val value = DHash.luminance(pixel)
            sum += value
            if (value < 8 || value > 247) clip++
        }
        val mean = if (pixels.isEmpty()) 0.0 else sum / pixels.size
        val clipFraction = if (pixels.isEmpty()) 0.0 else clip.toDouble() / pixels.size
        val exposure = QualityScorer.exposure(mean, clipFraction)
        val quality = QualityScorer.score(sharp, width, height, exposure, sizeBytes)
        return Features(hash, hist, sharp, exposure, quality)
    }
}
