package com.pixora.gallery.ui.cleanup

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixora.gallery.LocalGraph
import com.pixora.gallery.R
import com.pixora.gallery.cleanup.CleanupReason
import com.pixora.gallery.core.formatBytes
import com.pixora.gallery.core.parseIds
import com.pixora.gallery.data.db.ExclusionEntity
import com.pixora.gallery.data.db.MediaItemEntity
import com.pixora.gallery.data.db.SuggestionEntity
import com.pixora.gallery.ui.EmptyText
import com.pixora.gallery.ui.JobBanner
import com.pixora.gallery.ui.Thumb
import com.pixora.gallery.ui.home.factory
import com.pixora.gallery.ui.reasonLabel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CleanupViewModel(private val graph: com.pixora.gallery.AppGraph) : ViewModel() {
    val job = graph.jobs.state
    val suggestions = graph.db.suggestions().observe()
    val media = graph.db.media().observeImages()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)

    fun findDuplicates() = graph.jobs.launch("dup") {
        graph.jobs.hashDuplicates()
        graph.jobs.refreshSuggestions()
    }

    fun findSimilar() = graph.jobs.launch("similar") {
        graph.jobs.buildVisualFeatures(limit = null, label = false)
    }

    fun protect(ids: List<Long>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                graph.db.exclusions().upsert(ExclusionEntity("media:$id", "keep", now))
            }
            graph.jobs.refreshSuggestions()
        }
    }

    fun trash(uris: List<String>, onReady: (android.content.IntentSender) -> Unit) {
        if (uris.isEmpty()) return
        val pending = graph.trash.trashRequest(uris.map(Uri::parse))
        onReady(pending.intentSender)
    }

    fun afterTrash(ok: Boolean) {
        viewModelScope.launch {
            val text = if (ok) graph.appContext.getString(R.string.trash_done, 0) else graph.appContext.getString(R.string.trash_cancelled)
            messages.emit(if (ok) graph.appContext.getString(R.string.trash_done_short) else text)
            graph.jobs.launch("index") {
                graph.jobs.indexLibrary()
                graph.jobs.refreshSuggestions()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen() {
    val graph = LocalGraph.current
    val vm = viewModel<CleanupViewModel>(factory = factory { CleanupViewModel(graph) })
    val suggestions by vm.suggestions.collectAsState(emptyList())
    val media by vm.media.collectAsState(emptyList())
    val job by vm.job.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val byId = remember(media) { media.associateBy { it.id } }
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.afterTrash(result.resultCode == Activity.RESULT_OK)
        chosen = emptySet()
    }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_cleanup)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { JobBanner(job) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::findDuplicates, enabled = !job.running) { Text(stringResource(R.string.cleanup_exact)) }
                    Button(onClick = vm::findSimilar, enabled = !job.running) { Text(stringResource(R.string.cleanup_similar)) }
                }
            }
            item { Text(stringResource(R.string.quality_keep), style = MaterialTheme.typography.bodySmall) }
            if (suggestions.isEmpty()) {
                item { EmptyText(stringResource(R.string.empty_groups)) }
            }
            items(suggestions, key = { it.id }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    media = byId,
                    chosen = chosen,
                    onToggle = { id ->
                        chosen = if (id in chosen) chosen - id else chosen + id
                    },
                    onProtect = { ids -> vm.protect(ids) },
                )
            }
            item {
                val selected = media.filter { it.id in chosen }
                Button(
                    onClick = {
                        vm.trash(selected.map { it.uri }) { sender ->
                            trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                    },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.summary_trash, chosen.size) + " · " + formatBytes(selected.sumOf { it.sizeBytes }))
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: SuggestionEntity,
    media: Map<Long, MediaItemEntity>,
    chosen: Set<Long>,
    onToggle: (Long) -> Unit,
    onProtect: (List<Long>) -> Unit,
) {
    val ids = parseIds(suggestion.mediaIds)
    val keep = parseIds(suggestion.keepIds)
    val reason = runCatching { CleanupReason.valueOf(suggestion.reason) }.getOrDefault(CleanupReason.SIMILAR_GROUP)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reasonLabel(reason, ids.size + keep.size, keep.size), style = MaterialTheme.typography.titleSmall)
            Text(formatBytes(suggestion.reclaimBytes), style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(keep.mapNotNull(media::get), key = { "k${it.id}" }) { item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(item.uri, 84.dp)
                        Text(stringResource(R.string.suggested_keep), style = MaterialTheme.typography.labelSmall)
                    }
                }
                items(ids.mapNotNull(media::get), key = { it.id }) { item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(item.uri, 84.dp)
                        Checkbox(checked = item.id in chosen, onCheckedChange = { onToggle(item.id) })
                    }
                }
            }
            OutlinedButton(onClick = { onProtect(ids) }) { Text(stringResource(R.string.protect)) }
        }
    }
}
