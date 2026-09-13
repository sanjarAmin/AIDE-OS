package com.osamu.aide.navigation

import android.util.Base64
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.osamu.aide.ui.projects.ProjectsScreen
import com.osamu.aide.ui.settings.SettingsCategory
import com.osamu.aide.ui.settings.SettingsScreen
import com.osamu.aide.ui.workspace.WorkspaceScreen
import org.koin.androidx.compose.koinViewModel
import java.io.File

object Routes {
    const val PROJECTS = "projects"
    const val SETTINGS_ROUTE = "settings?category={category}"
    const val SETTINGS = "settings"
    const val WORKSPACE = "workspace/{projectPath}"

    fun settings(category: String? = null): String =
        if (!category.isNullOrBlank()) "settings?category=$category" else "settings"

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
private const val ARG_CATEGORY = "category"

@Composable
fun AideNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.PROJECTS,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(300))
        },
    ) {

        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpenProject = { dir -> navController.navigate(Routes.workspace(dir)) },
                onOpenSettings = { navController.navigate(Routes.settings()) },
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
                onOpenSettings = { category -> navController.navigate(Routes.settings(category)) },
                viewModel = koinViewModel(),
            )
        }

        composable(
            route = Routes.SETTINGS_ROUTE,
            arguments = listOf(
                navArgument(ARG_CATEGORY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val catParam = entry.arguments?.getString(ARG_CATEGORY)
            val initialCategory = SettingsCategory.fromRouteKey(catParam)
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                initialCategory = initialCategory,
            )
        }
    }
}
