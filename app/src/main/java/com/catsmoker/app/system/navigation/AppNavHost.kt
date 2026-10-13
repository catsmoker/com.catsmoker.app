package com.catsmoker.app.system.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.catsmoker.app.features.about.AboutRoute
import com.catsmoker.app.features.about.DonateRoute
import com.catsmoker.app.features.about.PrivacyPolicyRoute
import com.catsmoker.app.features.about.TermsRoute
import com.catsmoker.app.features.settings.SettingsRoute
import com.catsmoker.app.features.logs.LogsRoute
import com.catsmoker.app.features.main.MainRoute
import com.catsmoker.app.features.editgamefiles.EditGameFilesRoute
import com.catsmoker.app.features.gamingtools.GamingToolsRoute
import com.catsmoker.app.features.permissions.PermissionRoute

@Composable
fun AppNavHost(navController: NavHostController, startDestination: String) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(Routes.MAIN) {
            MainRoute(onNavigate = { navController.navigate(it) })
        }
        composable(Routes.PERMISSION) {
            PermissionRoute(
                onDone = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.PERMISSION) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.GAMING_TOOLS) {
            GamingToolsRoute(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.EDIT_GAME_FILES) {
            EditGameFilesRoute(onBack = { navController.popBackStack() })
        }
        // Variant destinations: full links the spoof graph, playstore links an empty
        // block with the same FQN — src/main never imports spoof classes directly.
        spoofGraph(navController)
        composable(Routes.ABOUT) {
            AboutRoute(
                onBack = { navController.popBackStack() },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
                onDonate = { navController.navigate(Routes.DONATE) },
                onPrivacy = { navController.navigate(Routes.PRIVACY) },
                onTerms = { navController.navigate(Routes.TERMS) }
            )
        }
        composable(Routes.PRIVACY) {
            PrivacyPolicyRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.TERMS) {
            TermsRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate(Routes.PERMISSION) },
                onOpenLogs = { navController.navigate(Routes.LOGS) }
            )
        }
        composable(Routes.LOGS) {
            LogsRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.DONATE) {
            DonateRoute(onBack = { navController.popBackStack() })
        }
    }
}
