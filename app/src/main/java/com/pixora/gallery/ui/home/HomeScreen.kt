package com.pixora.gallery.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixora.gallery.AppGraph
import com.pixora.gallery.LocalGraph
import com.pixora.gallery.R
import com.pixora.gallery.core.formatBytes
import com.pixora.gallery.data.media.AccessLevel
import com.pixora.gallery.duplicates.DuplicateFinder
import com.pixora.gallery.duplicates.HashedFile
import com.pixora.gallery.ui.JobBanner
import com.pixora.gallery.ui.SkeletonCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUi(
    val access: AccessLevel = AccessLevel.NONE,
    val photos: Int = 0,
    val videos: Int = 0,
    val screenshots: Int = 0,
    val unprocessed: Int = 0,
    val duplicateGroups: Int? = null,
    val reclaim: Long = 0,
    val suggestions: Int = 0,
    val suggestionBytes: Long = 0,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    val job = graph.jobs.state
    val ui = combine(
        graph.db.media().observePhotoCount(),
        graph.db.media().observeVideoCount(),
        graph.db.media().observeScreenshotCount(),
        graph.db.media().observeUnprocessedScreenshots(),
    ) { photos, videos, shots, pending ->
        intArrayOf(photos, videos, shots, pending)
    }.combine(graph.db.media().observeHashed()) { counts, hashed ->
        counts to hashed
    }.combine(graph.db.suggestions().observe()) { pair, suggestions ->
        Triple(pair.first, pair.second, suggestions)
    }.combine(graph.settings.flow) { triple, settings ->
        val counts = triple.first
        val hashed = triple.second
        val suggestions = triple.third
        val groups = if (settings.duplicateScanAt == 0L) {
            null
        } else {
            DuplicateFinder.group(
                hashed.map { HashedFile(it.id, it.sha256.orEmpty(), it.sizeBytes, it.isFavorite, false) },
            )
        }
        HomeUi(
            access = graph.mediaStore.access(),
            photos = counts[0],
            videos = counts[1],
            screenshots = counts[2],
            unprocessed = counts[3],
            duplicateGroups = groups?.size,
            reclaim = groups?.sumOf { it.reclaimBytes } ?: 0L,
            suggestions = suggestions.size,
            suggestionBytes = suggestions.sumOf { it.reclaimBytes },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    fun startIndex() {
        graph.jobs.launch("index") { graph.jobs.indexLibrary() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onSettings: () -> Unit, onRoute: (String) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<HomeViewModel>(factory = factory { HomeViewModel(graph) })
    val ui by vm.ui.collectAsState()
    val job by vm.job.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (graph.mediaStore.access() != AccessLevel.NONE) vm.startIndex()
    }
    LaunchedEffect(ui.access) {
        if (ui.access != AccessLevel.NONE && ui.photos == 0 && ui.videos == 0 && !job.running) {
            vm.startIndex()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.offline_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JobBanner(job)
                    if (job.running && ui.photos == 0) {
                        SkeletonCard()
                        SkeletonCard()
                    }
                }
            }
            if (ui.access == AccessLevel.NONE) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.permission_body))
                            Button(onClick = { permissionLauncher.launch(mediaPermissions()) }) {
                                Text(stringResource(R.string.permission_grant))
                            }
                        }
                    }
                }
            }
            if (ui.access == AccessLevel.PARTIAL) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.partial_access))
                            TextButton(onClick = { permissionLauncher.launch(mediaPermissions()) }) {
                                Text(stringResource(R.string.permission_grant))
                            }
                        }
                    }
                }
            }
            item { StatCard(stringResource(R.string.stat_photos), ui.photos.toString()) }
            item { StatCard(stringResource(R.string.stat_videos), ui.videos.toString()) }
            item { StatCard(stringResource(R.string.stat_screenshots), ui.screenshots.toString()) }
            item { StatCard(stringResource(R.string.stat_unprocessed), ui.unprocessed.toString()) }
            item {
                StatCard(
                    stringResource(R.string.stat_duplicates),
                    ui.duplicateGroups?.toString() ?: stringResource(R.string.not_checked),
                    if (ui.duplicateGroups != null) formatBytes(ui.reclaim) else null,
                )
            }
            item {
                StatCard(
                    stringResource(R.string.stat_suggestions),
                    if (ui.suggestions == 0 && ui.duplicateGroups == null) stringResource(R.string.not_checked) else ui.suggestions.toString(),
                    formatBytes(ui.suggestionBytes),
                )
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.leave_hint), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onRoute("shots") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_shots)) }
                        Button(onClick = { onRoute("cleanup") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cleanup)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onRoute("albums") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_albums)) }
                        Button(onClick = { onRoute("notes") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.nav_notes)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, extra: String? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun <T : ViewModel> factory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <R : ViewModel> create(modelClass: Class<R>): R {
            @Suppress("UNCHECKED_CAST")
            return create() as R
        }
    }
