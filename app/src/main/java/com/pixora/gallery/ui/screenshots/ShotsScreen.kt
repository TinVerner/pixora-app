package com.pixora.gallery.ui.screenshots

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.pixora.gallery.core.AppLog
import com.pixora.gallery.R
import com.pixora.gallery.data.db.MediaItemEntity
import com.pixora.gallery.media.ShotOrigin
import com.pixora.gallery.media.ShotTriage
import com.pixora.gallery.ui.EmptyText
import com.pixora.gallery.ui.JobBanner
import com.pixora.gallery.ui.Thumb
import com.pixora.gallery.ui.originLabel
import com.pixora.gallery.ui.home.factory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ShotsViewModel(private val graph: com.pixora.gallery.AppGraph) : ViewModel() {
    val days = MutableStateFlow(0)
    val job = graph.jobs.state

    @OptIn(ExperimentalCoroutinesApi::class)
    val shots = days.flatMapLatest { value ->
        val from = if (value <= 0) 0L else {
            val seconds = TimeUnit.DAYS.toSeconds(value.toLong())
            (System.currentTimeMillis() / 1000L) - seconds
        }
        graph.db.media().observeScreenshots(from)
    }

    fun setDays(value: Int) { days.value = value }

    fun analyze(includeNoise: Boolean = false) {
        AppLog.i("analyze clicked, noise=$includeNoise, jobActive=${graph.jobs.running()}")
        graph.jobs.launch("ocr") { graph.jobs.recognizeScreenshots(includeNoise = includeNoise) }
    }

    fun pause() = graph.jobs.pause()
    fun resume() = graph.jobs.resume()
    fun stop() = graph.jobs.cancel()
    fun index() {
        viewModelScope.launch { graph.jobs.launch("index") { graph.jobs.indexLibrary() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotsScreen(onReview: () -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<ShotsViewModel>(factory = factory { ShotsViewModel(graph) })
    val shots by vm.shots.collectAsState(emptyList())
    val job by vm.job.collectAsState()
    var days by remember { mutableIntStateOf(0) }
    var group by remember { mutableIntStateOf(0) }
    val visible = shots.filter { shot ->
        val origin = ShotTriage.origin(shot.displayName)
        when (group) {
            1 -> origin == ShotOrigin.CHAT
            2 -> origin == ShotOrigin.GAME
            else -> origin.worthReading
        }
    }
    val usefulCount = shots.count { ShotTriage.origin(it.displayName).worthReading }
    val periods = listOf(0 to R.string.period_all, 7 to R.string.period_7, 30 to R.string.period_30, 90 to R.string.period_90, 365 to R.string.period_365)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.shots_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(periods) { (value, label) ->
                    FilterChip(
                        selected = days == value,
                        onClick = {
                            days = value
                            vm.setDays(value)
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = group == 0, onClick = { group = 0 }, label = { Text(stringResource(R.string.group_useful)) })
                }
                item {
                    FilterChip(selected = group == 1, onClick = { group = 1 }, label = { Text(stringResource(R.string.lane_chat)) })
                }
                item {
                    FilterChip(selected = group == 2, onClick = { group = 2 }, label = { Text(stringResource(R.string.lane_game)) })
                }
            }
            JobBanner(job)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.analyze(false) }, enabled = !job.running) { Text(stringResource(R.string.analyze)) }
                if (job.running && !job.paused) OutlinedButton(onClick = vm::pause) { Text(stringResource(R.string.pause)) }
                if (job.running && job.paused) OutlinedButton(onClick = vm::resume) { Text(stringResource(R.string.resume)) }
                if (job.running) OutlinedButton(onClick = vm::stop) { Text(stringResource(R.string.stop)) }
            }
            if (!job.running) {
                TextButton(onClick = { vm.analyze(true) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.analyze_all))
                }
            }
            Text(
                stringResource(R.string.review_ready, usefulCount),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Button(onClick = onReview, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.action_review))
            }
            if (visible.isEmpty()) {
                EmptyText(stringResource(if (shots.isEmpty()) R.string.empty_shots else R.string.empty_lane))
                if (shots.isEmpty()) {
                    OutlinedButton(onClick = vm::index, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { shot -> ShotRow(shot) }
                }
            }
        }
    }
}

@Composable
private fun ShotRow(shot: MediaItemEntity) {
    Row(
        Modifier.fillMaxWidth().clickable { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumb(shot.uri, 72.dp)
        Column {
            Text(originLabel(ShotTriage.origin(shot.displayName)), style = MaterialTheme.typography.labelMedium)
            Text(shot.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(shot.bucketName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
