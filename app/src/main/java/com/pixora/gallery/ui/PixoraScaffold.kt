package com.pixora.gallery.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pixora.gallery.R
import com.pixora.gallery.ui.albums.AlbumScreen
import com.pixora.gallery.ui.albums.AlbumsScreen
import com.pixora.gallery.ui.cleanup.CleanupScreen
import com.pixora.gallery.ui.home.HomeScreen
import com.pixora.gallery.ui.notes.NoteDetailScreen
import com.pixora.gallery.ui.notes.NotesScreen
import com.pixora.gallery.ui.screenshots.ReviewScreen
import com.pixora.gallery.ui.screenshots.ShotsScreen
import com.pixora.gallery.ui.settings.SettingsScreen

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", R.string.nav_home, Icons.Filled.Home),
    Tab("shots", R.string.nav_shots, Icons.Filled.Image),
    Tab("notes", R.string.nav_notes, Icons.Filled.AutoAwesome),
    Tab("cleanup", R.string.nav_cleanup, Icons.Filled.DeleteSweep),
    Tab("albums", R.string.nav_albums, Icons.Filled.PhotoLibrary),
)

@Composable
fun PixoraScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = tabs.any { it.route == destination?.route }
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = {
                                Text(
                                    stringResource(tab.label),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") { HomeScreen(onSettings = { nav.navigate("settings") }, onRoute = { nav.navigate(it) }) }
            composable("shots") { ShotsScreen(onReview = { nav.navigate("review") }) }
            composable("review") { ReviewScreen(onBack = { nav.popBackStack() }) }
            composable("notes") { NotesScreen(onOpen = { nav.navigate("note/$it") }) }
            composable("note/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                NoteDetailScreen(id = id, onBack = { nav.popBackStack() })
            }
            composable("cleanup") { CleanupScreen() }
            composable("albums") { AlbumsScreen(onOpen = { nav.navigate("album/$it") }) }
            composable("album/{key}") { entry ->
                val key = entry.arguments?.getString("key").orEmpty()
                AlbumScreen(key = key, onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}
