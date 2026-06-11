package com.klipperremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.klipperremote.app.ui.screens.HomeScreen
import com.klipperremote.app.ui.screens.SettingsScreen
import com.klipperremote.app.ui.theme.KlipperRemoteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KlipperRemoteTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(onNavigateToSettings = { navController.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen(onNavigateBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
