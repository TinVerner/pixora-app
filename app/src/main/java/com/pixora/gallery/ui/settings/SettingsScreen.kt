package com.pixora.gallery.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.pixora.gallery.data.prefs.ThemeMode
import com.pixora.gallery.data.prefs.UserSettings
import com.pixora.gallery.ui.BackButton
import com.pixora.gallery.ui.home.factory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: com.pixora.gallery.AppGraph) : ViewModel() {
    val settings = graph.settings.flow
    val buckets = graph.db.media().observeBuckets()
    val exclusions = graph.db.exclusions().observe()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    fun export(uri: android.net.Uri, format: String) {
        viewModelScope.launch {
            graph.appContext.contentResolver.openOutputStream(uri)?.use { graph.notes.writeExport(it, format) }
            messages.emit(graph.appContext.getString(R.string.done))
        }
    }

    fun import(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val report = graph.appContext.contentResolver.openInputStream(uri)?.use { graph.notes.importStream(it) }
                messages.emit(graph.appContext.getString(R.string.imported, report?.first ?: 0, report?.second ?: 0))
            } catch (error: Exception) {
                messages.emit(graph.appContext.getString(R.string.backup_error))
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            graph.db.media().clearAnalysisCache()
            graph.db.ocr().clear()
            graph.db.analyses().clearAutomatic()
            graph.db.suggestions().clear()
            graph.db.suggestions().clearGroups()
            messages.emit(graph.appContext.getString(R.string.done))
        }
    }

    fun resetResults() {
        viewModelScope.launch {
            graph.db.analyses().clearAll()
            graph.db.suggestions().clear()
            graph.db.suggestions().clearGroups()
            messages.emit(graph.appContext.getString(R.string.done))
        }
    }

    fun deleteNotes() {
        viewModelScope.launch {
            graph.notes.clear()
            messages.emit(graph.appContext.getString(R.string.done))
        }
    }

    fun clearExclusions() {
        viewModelScope.launch { graph.db.exclusions().clear() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val vm = viewModel<SettingsViewModel>(factory = factory { SettingsViewModel(graph) })
    val settings by vm.settings.collectAsState(UserSettings())
    val buckets by vm.buckets.collectAsState(emptyList())
    val exclusions by vm.exclusions.collectAsState(emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmNotes by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, "json")
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.export(uri, "csv")
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.export(uri, "md")
    }
    val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.export(uri, "txt")
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    androidx.compose.runtime.LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { BackButton(onBack) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.theme == ThemeMode.SYSTEM, { vm.update { it.copy(theme = ThemeMode.SYSTEM) } }, { Text(stringResource(R.string.theme_system)) })
                FilterChip(settings.theme == ThemeMode.LIGHT, { vm.update { it.copy(theme = ThemeMode.LIGHT) } }, { Text(stringResource(R.string.theme_light)) })
                FilterChip(settings.theme == ThemeMode.DARK, { vm.update { it.copy(theme = ThemeMode.DARK) } }, { Text(stringResource(R.string.theme_dark)) })
            }
            Toggle(stringResource(R.string.dynamic_color), settings.dynamicColor) { value -> vm.update { it.copy(dynamicColor = value) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.language == "ru", { vm.update { it.copy(language = "ru") } }, { Text(stringResource(R.string.lang_ru)) })
                FilterChip(settings.language == "en", { vm.update { it.copy(language = "en") } }, { Text(stringResource(R.string.lang_en)) })
                FilterChip(settings.language == "system", { vm.update { it.copy(language = "system") } }, { Text(stringResource(R.string.lang_system)) })
            }

            Text(stringResource(R.string.settings_scan), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.background), settings.backgroundEnabled) { value ->
                if (value && Build.VERSION.SDK_INT >= 33) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                vm.update { it.copy(backgroundEnabled = value) }
            }
            Text(stringResource(R.string.background_hint), style = MaterialTheme.typography.bodySmall)
            Toggle(stringResource(R.string.charging), settings.onlyCharging) { value -> vm.update { it.copy(onlyCharging = value) } }
            Toggle(stringResource(R.string.battery), settings.batteryNotLow) { value -> vm.update { it.copy(batteryNotLow = value) } }
            Toggle(stringResource(R.string.auto_classify), settings.autoClassify) { value -> vm.update { it.copy(autoClassify = value) } }
            Text(stringResource(R.string.interval, settings.intervalHours))
            Slider(
                value = settings.intervalHours.toFloat(),
                onValueChange = { value -> vm.update { it.copy(intervalHours = value.toInt()) } },
                valueRange = 6f..168f,
            )
            Text(stringResource(R.string.max_files, settings.maxFilesPerRun))
            Slider(
                value = settings.maxFilesPerRun.toFloat(),
                onValueChange = { value -> vm.update { it.copy(maxFilesPerRun = value.toInt()) } },
                valueRange = 10f..200f,
            )
            Text(stringResource(R.string.threshold, settings.similarityThreshold))
            Slider(
                value = settings.similarityThreshold.toFloat(),
                onValueChange = { value -> vm.update { it.copy(similarityThreshold = value.toInt()) } },
                valueRange = 4f..20f,
            )
            Text(stringResource(R.string.old_days, settings.oldScreenshotDays))
            Slider(
                value = settings.oldScreenshotDays.toFloat(),
                onValueChange = { value -> vm.update { it.copy(oldScreenshotDays = value.toInt()) } },
                valueRange = 14f..365f,
            )

            Text(stringResource(R.string.ocr_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.add_lang_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.excluded_albums), style = MaterialTheme.typography.titleMedium)
            buckets.forEach { bucket ->
                Toggle(bucket, bucket in settings.excludedBuckets) { checked ->
                    vm.update { current ->
                        val next = current.excludedBuckets.toMutableSet()
                        if (checked) next += bucket else next -= bucket
                        current.copy(excludedBuckets = next)
                    }
                }
            }
            Text(stringResource(R.string.protected_count, exclusions.size))
            OutlinedButton(onClick = vm::clearExclusions) { Text(stringResource(R.string.clear_exclusions)) }

            Text(stringResource(R.string.settings_data), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.originals_safe), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { exportJson.launch("pixora-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_json)) }
            Button(onClick = { exportCsv.launch("pixora-notes.csv") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_csv)) }
            Button(onClick = { exportMd.launch("pixora-notes.md") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_md)) }
            Button(onClick = { exportTxt.launch("pixora-notes.txt") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_txt)) }
            OutlinedButton(onClick = { importJson.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_json))
            }
            Text(stringResource(R.string.cache_body))
            OutlinedButton(onClick = vm::clearCache, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.clear_cache)) }
            Text(stringResource(R.string.reset_body))
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reset_results)) }
            Text(stringResource(R.string.notes_body))
            OutlinedButton(onClick = { confirmNotes = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_all_notes)) }
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodySmall)
            scope.hashCode()
        }
    }
    if (confirmNotes) {
        AlertDialog(
            onDismissRequest = { confirmNotes = false },
            title = { Text(stringResource(R.string.delete_all_notes)) },
            text = { Text(stringResource(R.string.notes_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmNotes = false
                    vm.deleteNotes()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmNotes = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_results)) },
            text = { Text(stringResource(R.string.reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetResults()
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
