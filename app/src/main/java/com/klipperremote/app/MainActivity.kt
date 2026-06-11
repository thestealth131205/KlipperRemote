package com.klipperremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.klipperremote.app.ui.screens.GCodeViewerScreen
import com.klipperremote.app.ui.screens.HomeScreen
import com.klipperremote.app.ui.screens.PrintProgressBar
import com.klipperremote.app.ui.screens.SettingsScreen
import com.klipperremote.app.ui.theme.KlipperRemoteTheme
import com.klipperremote.app.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KlipperRemoteTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsState()
                val navController = rememberNavController()
                Box(modifier = Modifier.fillMaxSize()) {
                // Schwebender Fortschrittsbalken – liegt über allem außer Dialogen/Overlays
                PrintProgressBar(
                    progress = uiState.printProgress,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(10f)
                )
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (uiState.printProgress != null) 60.dp else 0.dp)
                ) {
                    composable("home") {
                        HomeScreen(
                            onNavigateToSettings = { navController.navigate("settings") },
                            onOpenGCodeViewer = { filename ->
                                val encoded = java.net.URLEncoder.encode(filename, "UTF-8")
                                navController.navigate("gcode_viewer/$encoded")
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable(
                        "gcode_viewer/{filename}",
                        arguments = listOf(navArgument("filename") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val encoded = backStackEntry.arguments?.getString("filename") ?: ""
                        val filename = URLDecoder.decode(encoded, "UTF-8")
                        GCodeViewerScreen(
                            filename = filename,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
                } // Box
            }
        }
    }
}
