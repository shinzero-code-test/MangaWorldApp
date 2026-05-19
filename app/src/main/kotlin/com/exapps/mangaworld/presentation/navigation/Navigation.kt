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
import com.exapps.mangaworld.presentation.latest.LatestUpdatesScreen
import com.exapps.mangaworld.presentation.localstorage.LocalStorageScreen
import com.exapps.mangaworld.presentation.reader.ReaderScreen
import com.exapps.mangaworld.presentation.search.SearchScreen
import com.exapps.mangaworld.presentation.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home        : Screen("home")
    object Browse      : Screen("browse")
    object Search      : Screen("search")
    object Library     : Screen("library")
    object Settings    : Screen("settings")
    object Downloads   : Screen("downloads")
    object LocalStorage: Screen("local_storage")
    object LatestUpdates : Screen("latest_updates")
    object Detail : Screen("detail/{sourceId}/{slug}") {
        fun createRoute(sourceId: String, slug: String) = "detail/$sourceId/$slug"
    }
    object Reader : Screen("reader/{sourceId}/{mangaId}/{chapterUrl}") {
        fun createRoute(sourceId: String, mangaId: String, chapterUrl: String) =
            "reader/$sourceId/$mangaId/${java.net.URLEncoder.encode(chapterUrl, "UTF-8")}"
    }
    object ReaderDeepLink : Screen("reader_deep_link?sourceId={sourceId}&mangaId={mangaId}&chapterUrl={chapterUrl}")
}

val bottomNavItems: List<Triple<Screen, String, ImageVector>> = listOf(
    Triple(Screen.Home,         "الرئيسية",     Icons.Filled.Home),
    Triple(Screen.Browse,       "تصفح",         Icons.Filled.GridView),
    Triple(Screen.Search,       "بحث",          Icons.Filled.Search),
    Triple(Screen.Library,      "المكتبة",      Icons.Filled.BookmarkBorder),
    Triple(Screen.Downloads,    "التنزيلات",    Icons.Filled.Download),
    Triple(Screen.LocalStorage, "المحلي",       Icons.Filled.FolderOpen),
    Triple(Screen.Settings,     "الإعدادات",    Icons.Filled.Settings),
)

@Composable
fun MangaNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition  = { fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(220)) },
        exitTransition   = { fadeOut(tween(180)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(180)) },
        popEnterTransition  = { fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220)) },
        popExitTransition   = { fadeOut(tween(180)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(180)) }
    ) {
        composable(
            route = Screen.Home.route,
            deepLinks = listOf(navDeepLink { uriPattern = "mangaworld://screen/home" })
        ) {
            HomeScreen(
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) },
                onSeeAllLatest = { navController.navigate(Screen.LatestUpdates.route) }
            )
        }
        composable(Screen.Browse.route) {
            BrowseScreen(
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) }
            )
        }
        composable(
            route = Screen.Search.route,
            deepLinks = listOf(navDeepLink { uriPattern = "mangaworld://screen/search" })
        ) {
            SearchScreen(
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) }
            )
        }
        composable(Screen.Library.route) {
            LibraryScreen(
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) },
                onBrowseClick = { navController.navigate(Screen.Browse.route) }
            )
        }
        composable(Screen.Settings.route)    { SettingsScreen() }
        composable(
            route = Screen.Downloads.route,
            deepLinks = listOf(navDeepLink { uriPattern = "mangaworld://screen/downloads" })
        ) { DownloadsScreen() }
        composable(
            route = Screen.LatestUpdates.route,
            deepLinks = listOf(
                navDeepLink { uriPattern = "mangaworld://screen/latest_updates" },
                navDeepLink { uriPattern = "mangaworld://screen/latest-updates" }
            )
        ) {
            LatestUpdatesScreen(
                onBack = { navController.popBackStack() },
                onOpenChapter = { src, mangaId, chapterUrl ->
                    navController.navigate(Screen.Reader.createRoute(src, mangaId, chapterUrl))
                }
            )
        }
        composable(Screen.LocalStorage.route) {
            LocalStorageScreen(
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) }
            )
        }
        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("slug")     { type = NavType.StringType }
            ),
            deepLinks = listOf(navDeepLink { uriPattern = "mangaworld://manga/{sourceId}/{slug}" })
        ) { back ->
            val sourceId = back.arguments?.getString("sourceId") ?: return@composable
            val slug     = back.arguments?.getString("slug") ?: return@composable
            MangaDetailScreen(
                source = MangaSource.fromId(sourceId), slug = slug,
                onChapterClick = { chapterUrl, mangaId ->
                    navController.navigate(Screen.Reader.createRoute(sourceId, mangaId, chapterUrl))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Screen.Reader.route,
            arguments = listOf(
                navArgument("sourceId")   { type = NavType.StringType },
                navArgument("mangaId")    { type = NavType.StringType },
                navArgument("chapterUrl") { type = NavType.StringType }
            )
        ) { back ->
            val sourceId   = back.arguments?.getString("sourceId") ?: return@composable
            val mangaId    = back.arguments?.getString("mangaId") ?: return@composable
            val chapterUrl = java.net.URLDecoder.decode(
                back.arguments?.getString("chapterUrl") ?: "", "UTF-8"
            )
            ReaderScreen(
                source = MangaSource.fromId(sourceId), mangaId = mangaId,
                chapterUrl = chapterUrl, onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.ReaderDeepLink.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("mangaId") { type = NavType.StringType },
                navArgument("chapterUrl") { type = NavType.StringType }
            ),
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "mangaworld://reader?sourceId={sourceId}&mangaId={mangaId}&chapterUrl={chapterUrl}"
                }
            )
        ) { back ->
            val sourceId = back.arguments?.getString("sourceId") ?: return@composable
            val mangaId = back.arguments?.getString("mangaId") ?: return@composable
            val chapterUrl = back.arguments?.getString("chapterUrl") ?: return@composable
            ReaderScreen(
                source = MangaSource.fromId(sourceId),
                mangaId = mangaId,
                chapterUrl = chapterUrl,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
