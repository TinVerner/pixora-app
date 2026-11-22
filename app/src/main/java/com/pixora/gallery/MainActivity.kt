package com.pixora.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.pixora.gallery.ui.PixoraScaffold
import com.pixora.gallery.ui.theme.PixoraTheme

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph is not provided") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PixoraApp).graph
        setContent {
            val settings by graph.settings.flow.collectAsState(initial = com.pixora.gallery.data.prefs.UserSettings())
            PixoraTheme(settings.theme, settings.dynamicColor) {
                CompositionLocalProvider(LocalGraph provides graph) {
                    PixoraScaffold()
                }
            }
        }
    }
}
