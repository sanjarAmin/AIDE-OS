package com.osamu.aide.navigation

import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.osamu.aide.ui.projects.ProjectsScreen
import com.osamu.aide.ui.settings.SettingsScreen
import com.osamu.aide.ui.workspace.WorkspaceScreen
import org.koin.androidx.compose.koinViewModel
import java.io.File

object Routes {
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val WORKSPACE = "workspace/{projectPath}"

    fun workspace(projectDir: File): String {
        val encoded = Base64.encodeToString(
            projectDir.absolutePath.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        return "workspace/$encoded"
    }

    fun decodeWorkspacePath(encoded: String): String {
        return String(
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),
            Charsets.UTF_8,
        )
    }
}

private const val ARG_PROJECT_PATH = "projectPath"

@Composable
fun AideNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {

        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpenProject = { dir -> navController.navigate(Routes.workspace(dir)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.WORKSPACE,
            arguments = listOf(navArgument(ARG_PROJECT_PATH) { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString(ARG_PROJECT_PATH).orEmpty()
            val path = runCatching { Routes.decodeWorkspacePath(encoded) }.getOrDefault(encoded)
            WorkspaceScreen(
                projectDir = File(path),
                onNavigateBack = { navController.popBackStack() },
                viewModel = koinViewModel(),
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
