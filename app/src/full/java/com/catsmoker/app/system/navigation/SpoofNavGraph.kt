package com.catsmoker.app.system.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.catsmoker.app.features.spoofdevice.AppAssignmentScreen
import com.catsmoker.app.features.spoofdevice.DiagnosticsScreen
import com.catsmoker.app.features.spoofdevice.ProfileEditorScreen
import com.catsmoker.app.features.spoofdevice.ProfilesListScreen
import com.catsmoker.app.features.spoofdevice.SafeModeScreen
import com.catsmoker.app.features.spoofdevice.SpoofDeviceViewModel
import com.catsmoker.app.features.spoofdevice.SpoofRoute

/**
 * Full-variant spoof destinations.
 *
 * Same FQN/extension as the playstore variant's no-op: the shared [AppNavHost]
 * calls [spoofGraph] unconditionally and each variant links its own body, so
 * `src/main` never imports spoof classes directly (they are absent from the
 * Play Store compile). The spoof screens share one ViewModel via
 * `hiltViewModel(parentEntry)` — preserved from the pre-flavor AppNavHost.
 */
fun NavGraphBuilder.spoofGraph(navController: NavHostController) {
    composable(Routes.SPOOF_DEVICE) {
        SpoofRoute(
            onNavigate = { navController.navigate(it) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.SPOOF_PROFILES) { backStackEntry ->
        val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
        val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
        val uiState by viewModel.uiState.collectAsState()
        ProfilesListScreen(
            uiState = uiState,
            presets = viewModel.presetsForPicker(),
            onNavigateToEditor = { navController.navigate(Routes.SPOOF_EDITOR.replace("{profileId}", it)) },
            onCreateProfile = { name, preset -> viewModel.createProfile(name, preset) },
            onDeleteProfile = { viewModel.deleteProfile(it) },
            onShareProfile = { viewModel.shareProfile(it) },
            onConfirmImportProfile = { preview, chosenName ->
                viewModel.confirmImport(preview, chosenName)
            },
            onConfirmImportPreset = { preview, chosenName ->
                viewModel.confirmImportAsPreset(preview, chosenName)
            },
            onDeletePreset = { viewModel.deleteUserPreset(it) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.SPOOF_EDITOR) { backStackEntry ->
        val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
        val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
        val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
        val uiState by viewModel.uiState.collectAsState()
        ProfileEditorScreen(
            profileId = profileId,
            uiState = uiState,
            presets = viewModel.presetsForPicker(),
            onSave = { id, name, profile ->
                viewModel.updateProfile(id, name, profile)
                navController.popBackStack()
            },
            onSavePreset = { name, profile -> viewModel.createUserPreset(name, profile) },
            onDeletePreset = { viewModel.deleteUserPreset(it) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.SPOOF_APPS) {
        val parentEntry = remember(it) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
        val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
        val uiState by viewModel.uiState.collectAsState()
        AppAssignmentScreen(
            uiState = uiState,
            onLoadApps = { viewModel.loadApps() },
            onAssignProfile = { pkg, id -> viewModel.assignProfile(pkg, id) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.SPOOF_SAFE_MODE) {
        val parentEntry = remember(it) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
        val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
        val uiState by viewModel.uiState.collectAsState()
        SafeModeScreen(
            uiState = uiState,
            onLoadApps = { viewModel.loadApps() },
            onToggleSafeMode = { pkg, enabled -> viewModel.toggleSafeMode(pkg, enabled) },
            onToggleVersionBypass = { pkg, enabled -> viewModel.toggleVersionBypass(pkg, enabled) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(Routes.SPOOF_DIAGNOSTICS) {
        DiagnosticsScreen(onBack = { navController.popBackStack() })
    }
}
