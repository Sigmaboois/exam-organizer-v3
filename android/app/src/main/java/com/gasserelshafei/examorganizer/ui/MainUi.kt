@file:OptIn(ExperimentalMaterial3Api::class)

package com.gasserelshafei.examorganizer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import com.gasserelshafei.examorganizer.AppState
import com.gasserelshafei.examorganizer.Screen

private fun Screen.icon(): ImageVector = when (this) {
    Screen.ORGANISE -> Icons.Outlined.Folder
    Screen.CHECK -> Icons.Outlined.FactCheck
    Screen.CLASSES -> Icons.Outlined.Groups
    Screen.LESSONS -> Icons.Outlined.VideoCall
}

/** Phones: tabs along the bottom. Tablets: a rail down the side. */
@Composable
fun ExamOrganizerUi(state: AppState) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) {
        val toast = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(toast.message, withDismissAction = true, duration = SnackbarDuration.Short)
        if (state.toast?.id == toast.id) state.toast = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (wide) "Exam Organizer" else state.screen.title) },
                actions = {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Outlined.HelpOutline, "How it works") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            if (!wide) NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(selected = state.screen == screen, onClick = { state.screen = screen },
                        icon = { Icon(screen.icon(), null) }, label = { Text(screen.title) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) NavigationRail {
                Screen.entries.forEach { screen ->
                    NavigationRailItem(selected = state.screen == screen, onClick = { state.screen = screen },
                        icon = { Icon(screen.icon(), null) }, label = { Text(screen.title) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (state.screen) {
                    Screen.ORGANISE -> OrganiseScreen(state, wide)
                    Screen.CHECK -> CheckScreen(state, wide)
                    Screen.CLASSES -> ClassesScreen(state, wide)
                    Screen.LESSONS -> LessonsScreen(state, wide)
                }
            }
        }
    }

    if (showSettings) SettingsDialog(state) { showSettings = false }
    if (showHelp) HelpDialog { showHelp = false }
    if (state.readingScan) ReadingScanDialog()
    ScanDialog(state)
}
