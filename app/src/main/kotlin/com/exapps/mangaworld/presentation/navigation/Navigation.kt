package com.exapps.mangaworld.presentation.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.*
import androidx.navigation.compose.*
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.browse.BrowseScreen
import com.exapps.mangaworld.presentation.detail.MangaDetailScreen
import com.exapps.mangaworld.presentation.downloads.DownloadsScreen
import com.exapps.mangaworld.presentation.home.HomeScreen
import com.exapps.mangaworld.presentation.library.LibraryScreen
import com.exapps.mangaworld.presentation.reader.ReaderScreen
import com.exapps.mangaworld.presentation.search.SearchScreen
import com.exapps.mangaworld.presentation.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home    : Screen("home")
    object Browse  : Screen("browse")
    object Search  : Screen("search")
    object Library : Screen("library")
    object Settings: Screen("settings")
    object Downloads: Screen("downloads")
    object Detail  : Screen("detail/{sourceId}/{slug}") {
        fun createRoute(sourceId: String, slug: String) = "detail/$sourceId/$slug"
    }
    object Reader  : Screen("reader/{sourceId}/{mangaId}/{chapterUrl}") {
        fun createRoute(sourceId: String, mangaId: String, chapterUrl: String) =
            "reader/$sourceId/$mangaId/${java.net.URLEncoder.encode(chapterUrl, "UTF-8")}"
    }
}

val bottomNavItems: List<Triple<Screen, String, ImageVector>> = listOf(
    Triple(Screen.Home,     "الرئيسية",  Icons.Filled.Home),
    Triple(Screen.Browse,   "تصفح",      Icons.Filled.GridView),
    Triple(Screen.Search,   "بحث",       Icons.Filled.Search),
    Triple(Screen.Library,  "المكتبة",   Icons.Filled.BookmarkBorder),
    Triple(Screen.Downloads, "التنزيلات", Icons.Filled.Download),
    Triple(Screen.Settings, "الإعدادات", Icons.Filled.Settings),
)

@Composable
fun MangaNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = {
            fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(220))
        },
        exitTransition = {
            fadeOut(tween(180)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(180))
        },
        popEnterTransition = {
            fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220))
        },
        popExitTransition = {
            fadeOut(tween(180)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(180))
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onMangaClick = { sourceId, slug ->
                    navController.navigate(Screen.Detail.createRoute(sourceId, slug))
                },
                onSeeAllLatest = { navController.navigate(Screen.Browse.route) }
            )
        }

        composable(Screen.Browse.route) {
            BrowseScreen(
                onMangaClick = { sourceId, slug ->
                    navController.navigate(Screen.Detail.createRoute(sourceId, slug))
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onMangaClick = { sourceId, slug ->
                    navController.navigate(Screen.Detail.createRoute(sourceId, slug))
                }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                onMangaClick = { sourceId, slug ->
                    navController.navigate(Screen.Detail.createRoute(sourceId, slug))
                },
                onBrowseClick = { navController.navigate(Screen.Browse.route) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }

        composable(Screen.Downloads.route) {
            DownloadsScreen()
        }

        composable(
            Screen.Detail.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("slug") { type = NavType.StringType }
            )
        ) { back ->
            val sourceId = back.arguments?.getString("sourceId") ?: return@composable
            val slug = back.arguments?.getString("slug") ?: return@composable
            MangaDetailScreen(
                source = MangaSource.fromId(sourceId),
                slug = slug,
                onChapterClick = { chapterUrl, mangaId ->
                    navController.navigate(Screen.Reader.createRoute(sourceId, mangaId, chapterUrl))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Screen.Reader.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("mangaId") { type = NavType.StringType },
                navArgument("chapterUrl") { type = NavType.StringType }
            )
        ) { back ->
            val sourceId = back.arguments?.getString("sourceId") ?: return@composable
            val mangaId = back.arguments?.getString("mangaId") ?: return@composable
            val chapterUrl = java.net.URLDecoder.decode(
                back.arguments?.getString("chapterUrl") ?: "", "UTF-8"
            )
            ReaderScreen(
                source = MangaSource.fromId(sourceId),
                mangaId = mangaId,
                chapterUrl = chapterUrl,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
