package com.pixora.gallery.ui.screenshots

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.pixora.gallery.LocalGraph
import com.pixora.gallery.R
import com.pixora.gallery.classification.ContentCategory
import com.pixora.gallery.classification.ExplainCode
import com.pixora.gallery.core.formatBytes
import com.pixora.gallery.data.db.AnalysisEntity
import com.pixora.gallery.data.db.MediaItemEntity
import com.pixora.gallery.notes.NoteDeduplicator
import com.pixora.gallery.ops.CommitResult
import com.pixora.gallery.ops.NoteDraft
import com.pixora.gallery.ops.ReviewCommitItem
import com.pixora.gallery.ui.BackButton
import com.pixora.gallery.ui.EmptyText
import com.pixora.gallery.ui.Thumb
import com.pixora.gallery.ui.categoryLabel
import com.pixora.gallery.ui.explainLabel
import com.pixora.gallery.ui.home.factory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class ReviewCard(
    val media: MediaItemEntity,
    val analysis: AnalysisEntity,
    val duplicateOf: String?,
)

class ReviewViewModel(private val graph: com.pixora.gallery.AppGraph) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    val cards = combine(
        graph.db.media().observeScreenshots(0),
        graph.db.analyses().observeAll(),
    ) { media, analyses ->
        val byId = media.associateBy { it.id }
        analyses.mapNotNull { analysis ->
            if (analysis.status == "SKIPPED" || analysis.status == "TRASHED") return@mapNotNull null
            val item = byId[analysis.mediaId] ?: return@mapNotNull null
            ReviewCard(item, analysis, null)
        }
    }
    val events = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val prompt = MutableStateFlow(false)
    private var pendingTrash: List<Pair<Long, String>> = emptyList()

    fun update(card: ReviewCard, transform: (AnalysisEntity) -> AnalysisEntity) {
        viewModelScope.launch {
            graph.db.analyses().upsert(transform(card.analysis).copy(userEdited = true, updatedAt = System.currentTimeMillis()))
        }
    }

    fun skip(card: ReviewCard) {
        viewModelScope.launch {
            graph.db.analyses().setStatus(listOf(card.media.id), "SKIPPED", System.currentTimeMillis())
        }
    }

    fun mass(cards: List<ReviewCard>, save: Boolean? = null, trash: Boolean? = null, skip: Boolean = false) {
        viewModelScope.launch {
            if (skip) {
                graph.db.analyses().setStatus(cards.map { it.media.id }, "SKIPPED", System.currentTimeMillis())
                return@launch
            }
            cards.forEach { card ->
                graph.db.analyses().upsert(
                    card.analysis.copy(
                        saveChecked = save ?: card.analysis.saveChecked,
                        trashChecked = trash ?: card.analysis.trashChecked,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun requestCommit(cards: List<ReviewCard>) {
        if (cards.any { it.analysis.trashChecked && !it.analysis.saveChecked }) {
            prompt.value = true
        } else {
            commit(cards, false)
        }
    }

    fun confirmDiscard(cards: List<ReviewCard>) {
        prompt.value = false
        commit(cards, true)
    }

    fun dismissPrompt() { prompt.value = false }

    private fun commit(cards: List<ReviewCard>, discardConfirmed: Boolean) {
        viewModelScope.launch {
            val selected = cards.filter { it.analysis.saveChecked || it.analysis.trashChecked }
            val items = selected.map { card ->
                val title = card.analysis.userTitle.orEmpty().ifBlank { card.analysis.title.orEmpty() }
                val category = card.analysis.userCategory ?: card.analysis.category
                val year = card.analysis.userYear
                ReviewCommitItem(
                    mediaId = card.media.id,
                    uri = card.media.uri,
                    save = card.analysis.saveChecked,
                    trash = card.analysis.trashChecked,
                    discardWithoutSaveConfirmed = discardConfirmed,
                    draft = if (!card.analysis.saveChecked) null else NoteDraft(
                        mediaId = card.media.id,
                        category = category,
                        title = title,
                        year = year,
                        body = card.analysis.fullText,
                        sourceText = card.analysis.fullText,
                        tags = "",
                        dedupKey = NoteDeduplicator.dedupKey(category, title, year),
                        allowDuplicate = card.analysis.allowDuplicate,
                    ),
                )
            }
            when (val result = graph.coordinator.commit(items)) {
                is CommitResult.Failed -> messages.emit(graph.appContext.getString(failText(result.message)))
                is CommitResult.NeedDiscardConfirmation -> prompt.value = true
                is CommitResult.Ready -> {
                    if (result.trashUris.isEmpty()) {
                        messages.emit(graph.appContext.getString(R.string.saved_only, result.savedIds.size))
                    } else {
                        pendingTrash = items.filter { it.uri in result.trashUris.toSet() }.map { it.mediaId to it.uri }
                        val pending = graph.trash.trashRequest(result.trashUris.map(Uri::parse))
                        events.emit(pending.intentSender)
                    }
                }
            }
        }
    }

    fun onTrashResult(ok: Boolean) {
        viewModelScope.launch {
            if (!ok) {
                messages.emit(graph.appContext.getString(R.string.trash_cancelled))
                pendingTrash = emptyList()
                return@launch
            }
            val verified = pendingTrash.filter { (_, uri) ->
                graph.mediaStore.isTrashedOrMissing(Uri.parse(uri), video = false)
            }
            if (verified.isNotEmpty()) {
                graph.db.analyses().setStatus(verified.map { it.first }, "TRASHED", System.currentTimeMillis())
            }
            val text = if (verified.size == pendingTrash.size) {
                graph.appContext.getString(R.string.trash_done, verified.size)
            } else {
                graph.appContext.getString(R.string.trash_partial, verified.size, pendingTrash.size)
            }
            messages.emit(text)
            pendingTrash = emptyList()
        }
    }

    private fun failText(code: String): Int = when (code) {
        "verify-failed" -> R.string.verify_failed
        else -> R.string.save_failed
    }

    fun candidates(raw: String): List<String> = runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<ReviewViewModel>(factory = factory { ReviewViewModel(graph) })
    val cards by vm.cards.collectAsState(emptyList())
    val prompt by vm.prompt.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var preview by remember { mutableStateOf<String?>(null) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onTrashResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(vm) {
        launch {
            vm.events.collect { sender ->
                trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
        launch {
            vm.messages.collect { snackbar.showSnackbar(it) }
        }
    }
    val saveCount = cards.count { it.analysis.saveChecked }
    val trashCards = cards.filter { it.analysis.trashChecked }
    val trashBytes = trashCards.sumOf { it.media.sizeBytes }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.review_title), maxLines = 1) },
                    navigationIcon = { BackButton(onBack) },
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { vm.mass(cards, save = true) }) { Text(stringResource(R.string.mass_save)) }
                    TextButton(onClick = { vm.mass(cards, skip = true) }) { Text(stringResource(R.string.mass_skip)) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.summary_save, saveCount))
                    Text(stringResource(R.string.summary_trash, trashCards.size))
                    Text(stringResource(R.string.summary_free, formatBytes(trashBytes)))
                    Text(stringResource(R.string.system_trash_hint), style = MaterialTheme.typography.labelSmall)
                    Button(
                        onClick = { vm.requestCommit(cards) },
                        enabled = saveCount + trashCards.size > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.check_action)) }
                }
            }
        },
    ) { padding ->
        if (cards.isEmpty()) {
            EmptyText(stringResource(R.string.empty_review), Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(cards, key = { it.media.id }) { card ->
                    ReviewCardView(
                        card = card,
                        candidates = vm.candidates(card.analysis.candidatesJson),
                        onChange = { vm.update(card, it) },
                        onSkip = { vm.skip(card) },
                        onPreview = { preview = card.media.uri },
                    )
                }
            }
        }
    }
    if (prompt) {
        AlertDialog(
            onDismissRequest = vm::dismissPrompt,
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = { TextButton(onClick = { vm.confirmDiscard(cards) }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = vm::dismissPrompt) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (preview != null) {
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AsyncImage(
                model = Uri.parse(preview),
                contentDescription = stringResource(R.string.open_photo),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewCardView(
    card: ReviewCard,
    candidates: List<String>,
    onChange: ((AnalysisEntity) -> AnalysisEntity) -> Unit,
    onSkip: () -> Unit,
    onPreview: () -> Unit,
) {
    val analysis = card.analysis
    var menu by remember { mutableStateOf(false) }
    val category = ContentCategory.fromName(analysis.userCategory ?: analysis.category)
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.padding(12.dp)) {
            val wide = maxWidth > 600.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.clickable(onClick = onPreview)) {
                        Thumb(card.media.uri, 220.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReviewFields(card, category, menu, candidates, { menu = it }, onChange, onSkip)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.clickable(onClick = onPreview)) {
                        Thumb(card.media.uri, 92.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReviewFields(card, category, menu, candidates, { menu = it }, onChange, onSkip)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewFields(
    card: ReviewCard,
    category: ContentCategory,
    menu: Boolean,
    candidates: List<String>,
    onMenu: (Boolean) -> Unit,
    onChange: ((AnalysisEntity) -> AnalysisEntity) -> Unit,
    onSkip: () -> Unit,
) {
    val analysis = card.analysis
    Text(card.media.displayName, style = MaterialTheme.typography.labelMedium)
    Text(stringResource(R.string.ocr_confidence, analysis.ocrConfidence))
    Text(stringResource(R.string.rule_confidence, analysis.confidence))
    Text(explainLabel(runCatching { ExplainCode.valueOf(analysis.explainCode) }.getOrDefault(ExplainCode.LOW_SIGNAL)), style = MaterialTheme.typography.bodySmall)
    ExposedDropdownMenuBox(expanded = menu, onExpandedChange = onMenu) {
        OutlinedTextField(
            value = categoryLabel(category),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
            ContentCategory.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(categoryLabel(item)) },
                    onClick = {
                        onMenu(false)
                        onChange { it.copy(userCategory = item.name) }
                    },
                )
            }
        }
    }
    OutlinedTextField(
        value = analysis.userTitle.orEmpty(),
        onValueChange = { value -> onChange { it.copy(userTitle = value) } },
        label = { Text(stringResource(R.string.field_title)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = analysis.userYear?.toString().orEmpty(),
        onValueChange = { value -> onChange { it.copy(userYear = value.toIntOrNull()) } },
        label = { Text(stringResource(R.string.field_year)) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (candidates.isNotEmpty()) {
        Text(stringResource(R.string.candidates), style = MaterialTheme.typography.labelMedium)
        candidates.forEach { candidate ->
            TextButton(onClick = { onChange { it.copy(userTitle = candidate) } }) { Text(candidate) }
        }
    }
    var showText by remember { mutableStateOf(false) }
    TextButton(onClick = { showText = !showText }) {
        Text(stringResource(if (showText) R.string.hide_text else R.string.show_text))
    }
    if (showText) {
        Text(
            analysis.fullText.ifBlank { stringResource(R.string.empty_title) },
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 4.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = analysis.saveChecked, onCheckedChange = { checked -> onChange { it.copy(saveChecked = checked) } })
        Text(stringResource(R.string.save_info))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = analysis.trashChecked, onCheckedChange = { checked -> onChange { it.copy(trashChecked = checked) } })
        Text(stringResource(R.string.trash_original))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = analysis.allowDuplicate, onCheckedChange = { checked -> onChange { it.copy(allowDuplicate = checked) } })
        Text(stringResource(R.string.allow_duplicate))
    }
    TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) }
}
