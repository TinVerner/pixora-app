package com.pixora.gallery.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixora.gallery.LocalGraph
import com.pixora.gallery.R
import com.pixora.gallery.classification.ContentCategory
import com.pixora.gallery.data.db.NoteEntity
import com.pixora.gallery.ui.BackButton
import com.pixora.gallery.ui.EmptyText
import com.pixora.gallery.ui.categoryLabel
import com.pixora.gallery.ui.home.factory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class NotesViewModel(private val graph: com.pixora.gallery.AppGraph) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow("all")
    val notes = combine(graph.notes.observe(), query, filter) { notes, query, filter ->
        notes.filter { note ->
            val text = "${note.title} ${note.body} ${note.tags} ${note.sourceText}".lowercase()
            val matchesQuery = query.isBlank() || text.contains(query.lowercase())
            val matchesFilter = when (filter) {
                "watch" -> ContentCategory.fromName(note.category).watchable && !note.watched
                "all" -> true
                else -> note.category == filter
            }
            matchesQuery && matchesFilter
        }
    }

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: String) { filter.value = value }

    fun export(uri: android.net.Uri, format: String) {
        viewModelScope.launch {
            graph.appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                graph.notes.writeExport(stream, format)
            }
        }
    }
}

class NoteDetailViewModel(private val graph: com.pixora.gallery.AppGraph, private val id: Long) : ViewModel() {
    val note = graph.notes.observeOne(id)
    fun save(note: NoteEntity) { viewModelScope.launch { graph.notes.update(note) } }
    fun delete() { viewModelScope.launch { graph.notes.delete(id) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onOpen: (Long) -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<NotesViewModel>(factory = factory { NotesViewModel(graph) })
    val notes by vm.notes.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    val filters = listOf(
        "all" to R.string.filter_all,
        "watch" to R.string.filter_watch,
        ContentCategory.MOVIE.name to R.string.cat_movie,
        ContentCategory.DORAMA.name to R.string.cat_dorama,
        ContentCategory.SERIES.name to R.string.cat_series,
        ContentCategory.ANIME.name to R.string.cat_anime,
        ContentCategory.RECIPE.name to R.string.cat_recipe,
        ContentCategory.NOTE.name to R.string.cat_note,
        ContentCategory.LINK.name to R.string.cat_link,
        ContentCategory.OTHER.name to R.string.filter_other,
    )
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.export(uri, "md")
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.notes_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.setQuery(it)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(stringResource(R.string.search)) },
                singleLine = true,
            )
            LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = {
                            filter = key
                            vm.setFilter(key)
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            TextButton(onClick = { exportLauncher.launch("pixora-notes.md") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.export))
            }
            if (notes.isEmpty()) {
                EmptyText(stringResource(R.string.empty_notes))
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes, key = { it.id }) { note ->
                        Card(onClick = { onOpen(note.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(note.title.ifBlank { stringResource(R.string.empty_title) }, style = MaterialThemeTitle())
                                Text(categoryLabel(ContentCategory.fromName(note.category)), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                if (note.watched) Text(stringResource(R.string.watched))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialThemeTitle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.titleMedium

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(id: Long, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<NoteDetailViewModel>(key = "note-$id", factory = factory { NoteDetailViewModel(graph, id) })
    val note by vm.note.collectAsState(null)
    val current = note
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notes_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (current == null) {
            EmptyText(stringResource(R.string.empty_notes), Modifier.padding(padding))
        } else {
            var title by remember(current.id, current.updatedAt) { mutableStateOf(current.title) }
            var body by remember(current.id, current.updatedAt) { mutableStateOf(current.body) }
            var tags by remember(current.id, current.updatedAt) { mutableStateOf(current.tags) }
            var category by remember(current.id, current.updatedAt) { mutableStateOf(current.category) }
            var year by remember(current.id, current.updatedAt) { mutableStateOf(current.year?.toString().orEmpty()) }
            var watched by remember(current.id, current.updatedAt) { mutableStateOf(current.watched) }
            var source by remember(current.id) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.field_title)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it }, label = { Text(stringResource(R.string.field_category)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(year, { year = it }, label = { Text(stringResource(R.string.field_year)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.tags)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.field_text)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.watched), modifier = Modifier.weight(1f))
                    Switch(checked = watched, onCheckedChange = { watched = it })
                }
                TextButton(onClick = { source = !source }) { Text(stringResource(R.string.source)) }
                if (source) Text(current.sourceText)
                Button(onClick = {
                    vm.save(
                        current.copy(
                            title = title,
                            body = body,
                            tags = tags,
                            category = category.ifBlank { ContentCategory.NOTE.name },
                            year = year.toIntOrNull(),
                            watched = watched,
                        ),
                    )
                }) { Text(stringResource(R.string.save_note)) }
                TextButton(onClick = {
                    vm.delete()
                    onBack()
                }) { Text(stringResource(R.string.delete_note)) }
                val exportOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                    if (uri != null) {
                        scope.launch {
                            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                                writer.write(title)
                                writer.write("\n")
                                writer.write(body)
                                writer.write("\n\n")
                                writer.write(current.sourceText)
                            }
                        }
                    }
                }
                TextButton(onClick = { exportOne.launch("note.txt") }) { Text(stringResource(R.string.export)) }
            }
        }
    }
}
