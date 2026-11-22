package com.pixora.gallery

import com.pixora.gallery.albums.LabelMapper
import com.pixora.gallery.classification.ContentCategory
import com.pixora.gallery.classification.HeuristicContentInterpreter
import com.pixora.gallery.cleanup.CleanupAdvisor
import com.pixora.gallery.cleanup.CleanupReason
import com.pixora.gallery.cleanup.PhotoFact
import com.pixora.gallery.duplicates.DHash
import com.pixora.gallery.duplicates.DuplicateFinder
import com.pixora.gallery.duplicates.HashedFile
import com.pixora.gallery.duplicates.QualityScorer
import com.pixora.gallery.duplicates.SimilarClusterer
import com.pixora.gallery.duplicates.VisualItem
import com.pixora.gallery.media.ReviewLane
import com.pixora.gallery.media.ScreenshotDetector
import com.pixora.gallery.media.ShotOrigin
import com.pixora.gallery.media.ShotTriage
import com.pixora.gallery.notes.NoteDeduplicator
import com.pixora.gallery.notes.NoteTransfer
import com.pixora.gallery.notes.NotesBackup
import com.pixora.gallery.notes.NotesCodec
import com.pixora.gallery.ops.BatchOperationCoordinator
import com.pixora.gallery.ops.CommitResult
import com.pixora.gallery.ops.NoteDraft
import com.pixora.gallery.ops.NoteWriter
import com.pixora.gallery.ops.ReviewCommitItem
import com.pixora.gallery.ops.WriteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    private val interpreter = HeuristicContentInterpreter()

    @Test
    fun extractsKoreanDramaTitleAndYear() {
        val text = "Топ-5 корейских дорам, которые стоит посмотреть. Истинная красота, 2020"
        val result = interpreter.interpret(text)
        assertEquals(ContentCategory.DORAMA, result.category)
        assertEquals("Истинная красота", result.title)
        assertEquals(2020, result.year)
        assertEquals(text, result.sourceText)
        assertTrue(result.confidence >= 60)
    }

    @Test
    fun extractsEnglishDramaTheSameWay() {
        val result = interpreter.interpret("Top 5 Korean dramas you should watch. True Beauty, 2020")
        assertEquals(ContentCategory.DORAMA, result.category)
        assertEquals("True Beauty", result.title)
        assertEquals(2020, result.year)
    }

    @Test
    fun categoryWordIsNotASavedTitle() {
        assertTrue(NoteDeduplicator.isCategoryLabel("DORAMA"))
        assertTrue(NoteDeduplicator.isCategoryLabel("SERIES"))
        assertTrue(NoteDeduplicator.isCategoryLabel("дорама"))
        assertTrue(!NoteDeduplicator.isCategoryLabel("Чёрное зеркало"))
        assertTrue(!NoteDeduplicator.isCategoryLabel("Следуя за сердцем"))
        val result = interpreter.interpret("DORAMA\nдорама\nтоп дорам")
        assertNull(result.title)
    }

    @Test
    fun ignoresTikTokChromeAndHandles() {
        val result = interpreter.interpret("@cooluser\nПодписаться\nДля вас\n12.4K\nНравится")
        assertNull(result.title)
        assertTrue(result.category == ContentCategory.OTHER || result.confidence < 40)
    }

    @Test
    fun doesNotInventATitle() {
        val result = interpreter.interpret("asdf qwer")
        assertNull(result.title)
        assertTrue(result.candidates.isEmpty() || result.title == null)
    }

    @Test
    fun keepsAmbiguousTitlesAsCandidates() {
        val result = interpreter.interpret("Истинная красота / Падающая звезда")
        assertNull(result.title)
        assertTrue(result.candidates.size >= 2)
    }

    @Test
    fun readsRecipeAndLinkWithoutGuessing() {
        val recipe = interpreter.interpret("Рецепт: Борщ\nИнгредиенты\nсвекла")
        assertEquals(ContentCategory.RECIPE, recipe.category)
        assertEquals("Борщ", recipe.title)
        val link = interpreter.interpret("https://example.com/watch")
        assertEquals(ContentCategory.LINK, link.category)
        assertTrue(link.title?.contains("example.com") == true)
    }

    @Test
    fun sortsTikTokAwayFromChatsAndGames() {
        assertEquals(ShotOrigin.TIKTOK, ShotTriage.origin("Screenshot_20261122_081232_TikTok.jpg"))
        assertEquals(ShotOrigin.CHAT, ShotTriage.origin("Screenshot_20261122_162330_AyuGram.jpg"))
        assertEquals(ShotOrigin.GAME, ShotTriage.origin("Screenshot_20261122_103039_Mobile Legends Bang Bang.jpg"))
        assertEquals(ShotOrigin.GAME, ShotTriage.origin("Screenshot_2022-10-31-17-21-17-581_com.miHoYo.GenshinImpact.jpg"))
        assertEquals(ShotOrigin.INSTAGRAM, ShotTriage.origin("Screenshot_20261101_120000_Instagram.jpg"))
        assertTrue(ShotTriage.origin("Screenshot_20261122_081232_TikTok.jpg").worthReading)
        assertTrue(!ShotTriage.origin("Screenshot_20261122_162330_AyuGram.jpg").worthReading)
        assertEquals(
            ReviewLane.TITLES,
            ShotTriage.lane(ShotOrigin.TIKTOK, ContentCategory.DORAMA, "Истинная красота", emptyList()),
        )
        assertEquals(
            ReviewLane.GAME,
            ShotTriage.lane(ShotOrigin.GAME, ContentCategory.NOTE, "Yd 7 mar д", emptyList()),
        )
        assertEquals(
            ReviewLane.CHAT,
            ShotTriage.lane(ShotOrigin.CHAT, ContentCategory.NOTE, "привет", emptyList()),
        )
    }

    @Test
    fun screenshotPaths() {
        assertTrue(ScreenshotDetector.isScreenshot("DCIM/Screenshots/", "Screenshot_20240101.jpg", "Screenshots"))
        assertTrue(ScreenshotDetector.isScreenshot("Pictures/Telegram/", "Снимок экрана.png", "Telegram"))
        assertTrue(!ScreenshotDetector.isScreenshot("DCIM/Camera/", "IMG_001.jpg", "Camera"))
    }

    @Test
    fun identicalPixelsShareHashAndSharpPhotoWins() {
        val width = 18
        val height = 16
        val pixels = IntArray(width * height) { index -> if (index % width < width / 2) 0xFF101010.toInt() else 0xFFEFEFEF.toInt() }
        val hash = DHash.compute(pixels, width, height)
        assertEquals(hash, DHash.compute(pixels.clone(), width, height))
        assertEquals(0, DHash.hamming(hash, hash))
        val sharp = QualityScorer.score(200.0, 2000, 1500, 0.8, 1_500_000)
        val blurryHuge = QualityScorer.score(1.0, 4000, 3000, 0.8, 8_000_000)
        assertTrue(sharp > blurryHuge)
    }

    @Test
    fun exactDuplicatesKeepFavorite() {
        val groups = DuplicateFinder.group(
            listOf(
                HashedFile(1, "aa", 10, favorite = false, excluded = false),
                HashedFile(2, "aa", 10, favorite = true, excluded = false),
                HashedFile(3, "aa", 10, favorite = false, excluded = false),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(2L, groups.first().keepId)
        assertEquals(listOf(1L, 3L), groups.first().deleteIds.sorted())
    }

    @Test
    fun similarBurstKeepsSharperSmallerPhoto() {
        val items = listOf(
            VisualItem(1, 7L, 1_000, 1000, 800, 5_000_000, quality = 0.2, sharpness = 2.0),
            VisualItem(2, 7L, 2_000, 800, 600, 400_000, quality = 0.9, sharpness = 80.0),
        )
        val pairs = SimilarClusterer.candidatePairs(items, burstMillis = 10_000)
        val groups = SimilarClusterer.cluster(items, pairs, threshold = 10, strict = 6, burstMillis = 10_000)
        assertEquals(1, groups.size)
        assertEquals(listOf(2L), groups.first().keepIds)
    }

    @Test
    fun labelMapperDoesNotClaimASpecificCat() {
        val labels = LabelMapper.map(listOf("Cat" to 0.91f, "Violin" to 0.2f))
        assertTrue(AlbumLabelsCats(labels))
        assertTrue("ANIMALS" in labels)
        assertTrue(LabelMapper.map(listOf("Violin" to 0.99f)).isEmpty())
    }

    @Test
    fun noteDedupAndBackupRoundTrip() {
        val left = NoteDeduplicator.dedupKey("DORAMA", "Истинная  красота", 2020)
        val right = NoteDeduplicator.dedupKey("DORAMA", "истинная красота", 2020)
        assertEquals(left, right)
        val folded = NoteDeduplicator.dedupKey("DORAMA", "Ёжик", 2020)
        val plain = NoteDeduplicator.dedupKey("DORAMA", "ежик", 2020)
        assertEquals(plain, folded)
        assertTrue(folded.contains("ежик"))
        assertTrue(!folded.contains("ё"))
        val backup = NotesBackup(
            version = 1,
            exportedAt = 10,
            notes = listOf(NoteTransfer(category = "NOTE", title = "A", body = "line")),
        )
        val parsed = NotesCodec.fromJson(NotesCodec.toJson(backup))
        assertEquals("A", parsed.notes.single().title)
        assertTrue(NotesCodec.toCsv(parsed.notes).contains("NOTE"))
    }

    @Test
    fun saveFailureDoesNotScheduleTrash() = runBlocking {
        val coordinator = BatchOperationCoordinator(FakeNotes(fail = true))
        val result = coordinator.commit(
            listOf(item(save = true, trash = true, confirmed = false)),
        )
        assertTrue(result is CommitResult.Failed)
    }

    @Test
    fun trashWithoutSaveNeedsExplicitConfirmation() = runBlocking {
        val coordinator = BatchOperationCoordinator(FakeNotes(fail = false))
        val blocked = coordinator.commit(listOf(item(save = false, trash = true, confirmed = false)))
        assertTrue(blocked is CommitResult.NeedDiscardConfirmation)
        val allowed = coordinator.commit(listOf(item(save = false, trash = true, confirmed = true)))
        assertTrue(allowed is CommitResult.Ready)
        assertEquals(listOf("uri"), (allowed as CommitResult.Ready).trashUris)
    }

    @Test
    fun successfulSaveCanTrashOnlyAfterVerification() = runBlocking {
        val notes = FakeNotes(fail = false)
        val coordinator = BatchOperationCoordinator(notes)
        val result = coordinator.commit(listOf(item(save = true, trash = true, confirmed = false))) as CommitResult.Ready
        assertEquals(listOf(1L), result.savedIds)
        assertEquals(listOf("uri"), result.trashUris)
        assertTrue(notes.verified)
    }

    @Test
    fun cleanupSkipsFavoritesAndExplainsSavedShots() {
        val photos = listOf(
            PhotoFact(1, 100, 1.0, screenshot = false, favorite = true, bucket = "Camera", dateModifiedMillis = 10),
            PhotoFact(2, 100, 1.0, screenshot = true, favorite = false, bucket = "Screenshots", dateModifiedMillis = 10),
        )
        val suggestions = CleanupAdvisor.advise(
            photos = photos,
            exact = emptyList(),
            similar = emptyList(),
            savedMediaIds = setOf(2),
            excludedIds = emptySet(),
            protectedBuckets = emptySet(),
            nowMillis = 1_000_000_000_000,
            oldScreenshotDays = 30,
        )
        assertTrue(suggestions.none { 1L in it.mediaIds })
        assertEquals(CleanupReason.ALREADY_SAVED, suggestions.first().reason)
    }

    private fun item(save: Boolean, trash: Boolean, confirmed: Boolean) = ReviewCommitItem(
        mediaId = 9,
        uri = "uri",
        save = save,
        trash = trash,
        discardWithoutSaveConfirmed = confirmed,
        draft = NoteDraft(9, "NOTE", "Title", null, "body", "body", "", "note|title|", false),
    )

    private fun AlbumLabelsCats(labels: Set<String>) = "CATS" in labels

    private class FakeNotes(private val fail: Boolean) : NoteWriter {
        var verified = false
        override suspend fun commit(drafts: List<NoteDraft>): WriteResult {
            if (fail) error("save-failed")
            return WriteResult(listOf(1L), drafts.map { it.mediaId }, 0)
        }

        override suspend fun allExist(ids: List<Long>): Boolean {
            verified = true
            return ids.isNotEmpty() || ids.isEmpty()
        }
    }
}
