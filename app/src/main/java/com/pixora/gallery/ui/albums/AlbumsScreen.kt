package com.pixora.gallery.ui.albums

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixora.gallery.LocalGraph
import com.pixora.gallery.R
import com.pixora.gallery.albums.AlbumLabels
import com.pixora.gallery.albums.LabelMapper
import com.pixora.gallery.albums.Signature
import com.pixora.gallery.albums.VisualSignature
import com.pixora.gallery.data.db.AlbumItemEntity
import com.pixora.gallery.data.db.ExemplarEntity
import com.pixora.gallery.data.db.MediaItemEntity
import com.pixora.gallery.data.db.SmartAlbumEntity
import com.pixora.gallery.ui.BackButton
import com.pixora.gallery.ui.EmptyText
import com.pixora.gallery.ui.JobBanner
import com.pixora.gallery.ui.Thumb
import com.pixora.gallery.ui.home.factory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class AlbumsViewModel(private val graph: com.pixora.gallery.AppGraph) : ViewModel() {
    val albums = graph.db.albums().observe()
    val images = graph.db.media().observeImages()
    val job = graph.jobs.state

    fun labelAll() = graph.jobs.launch("labels") {
        graph.jobs.buildVisualFeatures(limit = null, label = true)
    }

    fun create(name: String, exemplars: List<MediaItemEntity>, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = graph.db.albums().insert(SmartAlbumEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
            val now = System.currentTimeMillis()
            exemplars.forEach { media ->
                if (media.dHash == null || media.histogram.isNullOrBlank()) return@forEach
                graph.db.albums().upsertExemplar(
                    ExemplarEntity(id, media.id, media.dHash, media.histogram, now),
                )
            }
            onCreated(id)
        }
    }
}

class AlbumViewModel(private val graph: com.pixora.gallery.AppGraph, private val key: String) : ViewModel() {
    val images = graph.db.media().observeImages()
    val job = graph.jobs.state
    val personalId = key.removePrefix("user_").toLongOrNull()
    val items: Flow<List<AlbumItemEntity>> = personalId?.let { graph.db.albums().observeItems(it) } ?: flowOf(emptyList())

    fun match() {
        val id = personalId ?: return
        graph.jobs.launch("match") { graph.jobs.matchPersonal(id, 0.72f) }
    }

    fun decide(item: AlbumItemEntity, accept: Boolean) {
        viewModelScope.launch {
            val state = if (accept) "ACCEPTED" else "REJECTED"
            graph.db.albums().upsertItem(item.copy(state = state, updatedAt = System.currentTimeMillis()))
            if (accept) {
                val media = graph.db.media().get(item.mediaId) ?: return@launch
                if (media.dHash != null && !media.histogram.isNullOrBlank()) {
                    graph.db.albums().upsertExemplar(
                        ExemplarEntity(item.albumId, media.id, media.dHash, media.histogram, System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    fun addExemplar(albumId: Long, media: MediaItemEntity) {
        viewModelScope.launch {
            if (media.dHash == null || media.histogram.isNullOrBlank()) return@launch
            graph.db.albums().upsertExemplar(ExemplarEntity(albumId, media.id, media.dHash, media.histogram, System.currentTimeMillis()))
        }
    }

    fun move(uris: List<Uri>, name: String, onSender: (android.content.IntentSender) -> Unit) {
        if (uris.isEmpty()) return
        onSender(graph.mover.writeRequest(uris).intentSender)
    }

    fun applyMove(uris: List<Uri>, name: String, onDone: (Int, Int) -> Unit) {
        viewModelScope.launch {
            val path = graph.mover.relativePath(name)
            var ok = 0
            var failed = 0
            uris.forEach { uri ->
                when (graph.mover.updatePath(uri, path)) {
                    is com.pixora.gallery.data.media.PathUpdate.Updated -> ok++
                    is com.pixora.gallery.data.media.PathUpdate.Failed -> failed++
                }
            }
            onDone(ok, failed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(onOpen: (String) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<AlbumsViewModel>(factory = factory { AlbumsViewModel(graph) })
    val albums by vm.albums.collectAsState(emptyList())
    val images by vm.images.collectAsState(emptyList())
    val job by vm.job.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val counts = remember(images) {
        AlbumLabels.system.associateWith { label ->
            images.count { LabelMapper.parse(it.labels).contains(label) || (label == AlbumLabels.SCREENSHOTS && it.isScreenshot) }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_albums)) }) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    JobBanner(job)
                    Text(stringResource(R.string.albums_system), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = vm::labelAll, enabled = !job.running) { Text(stringResource(R.string.run_labels)) }
                    Text(stringResource(R.string.visual_only), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(AlbumLabels.system) { label ->
                Card(onClick = { onOpen("sys_$label") }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(systemAlbumName(label), style = MaterialTheme.typography.titleMedium)
                        Text(counts[label]?.toString() ?: "0")
                    }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(R.string.albums_personal), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { creating = true }) { Text(stringResource(R.string.create_album)) }
                }
            }
            items(albums, key = { it.id }) { album ->
                Card(onClick = { onOpen("user_${album.id}") }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(album.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.honest_match), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (creating) {
        CreateAlbumDialog(
            images = images,
            onDismiss = { creating = false },
            onCreate = { name, exemplars ->
                vm.create(name, exemplars) { id ->
                    creating = false
                    onOpen("user_$id")
                }
            },
        )
    }
}

@Composable
private fun systemAlbumName(label: String): String = stringResource(
    when (label) {
        AlbumLabels.SCREENSHOTS -> R.string.album_screenshots
        AlbumLabels.ANIMALS -> R.string.album_animals
        AlbumLabels.CATS -> R.string.album_cats
        AlbumLabels.DOGS -> R.string.album_dogs
        AlbumLabels.PEOPLE -> R.string.album_people
        AlbumLabels.NATURE -> R.string.album_nature
        AlbumLabels.FOOD -> R.string.album_food
        AlbumLabels.DOCUMENTS -> R.string.album_docs
        AlbumLabels.TRAVEL -> R.string.album_travel
        else -> R.string.cat_other
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateAlbumDialog(
    images: List<MediaItemEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, List<MediaItemEntity>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_album)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.album_name)) })
                Text(stringResource(R.string.examples))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.take(40).forEach { image ->
                        FilterChip(
                            selected = image.id in picked,
                            onClick = { picked = if (image.id in picked) picked - image.id else picked + image.id },
                            label = { Text(image.displayName.take(12)) },
                        )
                    }
                }
                Text(stringResource(R.string.picked, picked.size))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, images.filter { it.id in picked }) },
                enabled = name.isNotBlank() && picked.isNotEmpty(),
            ) { Text(stringResource(R.string.create_album)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(key: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<AlbumViewModel>(key = key, factory = factory { AlbumViewModel(graph, key) })
    val images by vm.images.collectAsState(emptyList())
    val items by vm.items.collectAsState(emptyList())
    val job by vm.job.collectAsState()
    val system = key.removePrefix("sys_").takeIf { key.startsWith("sys_") }
    val shown = if (system != null) {
        images.filter {
            LabelMapper.parse(it.labels).contains(system) || (system == AlbumLabels.SCREENSHOTS && it.isScreenshot)
        }
    } else {
        val wanted = items.filter { it.state != "REJECTED" }.map { it.mediaId }.toSet()
        images.filter { it.id in wanted }
    }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var moveName by remember { mutableStateOf("") }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var moveMessage by remember { mutableStateOf<String?>(null) }
    val movedOk = stringResource(R.string.move_ok)
    val movedFail = stringResource(R.string.move_fail)
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.applyMove(pendingUris, moveName) { ok, failed ->
                moveMessage = "$movedOk $ok / $movedFail $failed"
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (system != null) systemAlbumName(system) else stringResource(R.string.albums_personal)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    JobBanner(job)
                    if (system == null) {
                        Text(stringResource(R.string.honest_match))
                        Button(onClick = vm::match, enabled = !job.running) { Text(stringResource(R.string.find_matches)) }
                        Text(stringResource(R.string.no_features), style = MaterialTheme.typography.bodySmall)
                    }
                    if (moveMessage != null) Text(moveMessage!!)
                    OutlinedTextField(moveName, { moveName = it }, label = { Text(stringResource(R.string.album_name)) })
                    Text(stringResource(R.string.write_hint), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            val uris = shown.filter { it.id in selected }.map { Uri.parse(it.uri) }
                            pendingUris = uris
                            vm.move(uris, moveName) { sender ->
                                writeLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                        },
                        enabled = selected.isNotEmpty() && moveName.isNotBlank(),
                    ) { Text(stringResource(R.string.move_gallery)) }
                }
            }
            if (shown.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyText(stringResource(R.string.empty_album))
                }
            }
            items(shown, key = { it.id }) { image ->
                val match = items.firstOrNull { it.mediaId == image.id }
                Card(Modifier.clickable { selected = if (image.id in selected) selected - image.id else selected + image.id }) {
                    Column(Modifier.padding(8.dp)) {
                        Thumb(image.uri, 110.dp)
                        Text(image.displayName, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        if (match != null) {
                            Text("${(match.similarity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                            Row {
                                TextButton(onClick = { vm.decide(match, true) }) { Text(stringResource(R.string.accept)) }
                                TextButton(onClick = { vm.decide(match, false) }) { Text(stringResource(R.string.reject)) }
                            }
                        }
                        if (image.rawLabel != null) {
                            Text(stringResource(R.string.model_says, image.rawLabel, ((image.rawLabelScore ?: 0f) * 100).toInt()))
                        }
                        if (image.id in selected) Text(stringResource(R.string.suggested_keep))
                    }
                }
            }
        }
    }
}
