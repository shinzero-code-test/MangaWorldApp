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
import com.exapps.mangaworld.presentation.cloud.CloudSyncScreen
import com.exapps.mangaworld.presentation.community.CommunityChatScreen
import com.exapps.mangaworld.presentation.community.ModerationDashboardScreen
import com.exapps.mangaworld.presentation.community.CommunityScreen
import com.exapps.mangaworld.presentation.detail.MangaDetailScreen
import com.exapps.mangaworld.presentation.diagnostics.DiagnosticsScreen
import com.exapps.mangaworld.presentation.downloads.DownloadsScreen
import com.exapps.mangaworld.presentation.home.HomeScreen
import com.exapps.mangaworld.presentation.library.LibraryScreen
import com.exapps.mangaworld.presentation.latest.LatestUpdatesScreen
import com.exapps.mangaworld.presentation.localstorage.LocalStorageScreen
import com.exapps.mangaworld.presentation.notifications.NotificationCenterScreen
import com.exapps.mangaworld.presentation.profile.PublicProfileScreen
import com.exapps.mangaworld.presentation.profile.UserProfileScreen
import com.exapps.mangaworld.presentation.profile.UserListsScreen
import com.exapps.mangaworld.presentation.reader.ReaderScreen
import com.exapps.mangaworld.presentation.search.SearchScreen
import com.exapps.mangaworld.presentation.settings.SettingsScreen
import com.exapps.mangaworld.presentation.stats.ReadingStatsScreen
import com.exapps.mangaworld.presentation.collections.CollectionsScreen
import com.exapps.mangaworld.presentation.goals.GoalsScreen
import com.exapps.mangaworld.presentation.more.MoreScreen
import com.exapps.mangaworld.presentation.sources.SourcesScreen
import com.exapps.mangaworld.presentation.sources.SourceBrowseScreen
import com.exapps.mangaworld.presentation.localstorage.ImportMangaScreen
import com.exapps.mangaworld.presentation.suggestions.SuggestionsScreen
import com.exapps.mangaworld.presentation.auth.login.LoginScreen

sealed class Screen(val route: String) {
    object Home        : Screen("home")
    object Browse      : Screen("browse")
    object Search      : Screen("search")
    object Library     : Screen("library")
    object Settings    : Screen("settings")
    object Diagnostics : Screen("diagnostics")
    object CloudSync : Screen("cloud_sync")
    object Profile : Screen("profile")
    object Notifications : Screen("notifications")
    object CommunityChat : Screen("community_chat?roomId={roomId}&title={title}") {
        fun createRoute(roomId: String, title: String): String =
            "community_chat?roomId=${java.net.URLEncoder.encode(roomId, "UTF-8")}&title=${java.net.URLEncoder.encode(title, "UTF-8")}"
    }
    object UserLists : Screen("user_lists")
    object PublicProfile : Screen("public_profile/{userId}") {
        fun createRoute(userId: String) = "public_profile/$userId"
    }
    object ModerationDashboard : Screen("moderation_dashboard")
    object Downloads   : Screen("downloads")
    object LocalStorage: Screen("local_storage")
    object LatestUpdates : Screen("latest_updates")
    object ReadingStats : Screen("reading_stats")
    object Collections : Screen("collections/{collectionId}") {
        fun createRoute(collectionId: String = "") = if (collectionId.isBlank()) "collections/" else "collections/$collectionId"
    }
    object Goals : Screen("goals")
    object More : Screen("more")
    object Sources : Screen("sources")
    object SourceBrowse : Screen("source_browse/{sourceId}") {
        fun createRoute(sourceId: String) = "source_browse/$sourceId"
    }
    object ImportManga : Screen("import_manga")
    object Suggestions : Screen("suggestions")
    object Login : Screen("login")
    object Community : Screen("community/{sourceId}/{mangaId}/{slug}?chapterUrl={chapterUrl}&commentId={commentId}") {
        fun createRoute(sourceId: String, mangaId: String, slug: String, chapterUrl: String? = null, commentId: String? = null): String {
            val encoded = chapterUrl?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val encodedComment = commentId?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            return "community/$sourceId/$mangaId/$slug?chapterUrl=$encoded&commentId=$encodedComment"
        }
    }
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
    Triple(Screen.More,         "المزيد",       Icons.Filled.MoreHoriz),
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
        composable(Screen.Profile.route) {
            UserProfileScreen(
                onOpenCloudSync = { navController.navigate(Screen.CloudSync.route) },
                onOpenDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onOpenCommunityChat = { navController.navigate(Screen.CommunityChat.createRoute("global", "الدردشة العامة")) },
                onOpenNotifications = { navController.navigate(Screen.Notifications.route) },
                onOpenLists = { navController.navigate(Screen.UserLists.route) },
                onOpenModeration = { navController.navigate(Screen.ModerationDashboard.route) },
                onOpenReadingStats = { navController.navigate(Screen.ReadingStats.route) }
            )
        }
        composable(Screen.UserLists.route) {
            UserListsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.PublicProfile.route, arguments = listOf(navArgument("userId") { type = NavType.StringType })) {
            PublicProfileScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Notifications.route) {
            NotificationCenterScreen(
                onBack = { navController.popBackStack() },
                onOpenThread = { item ->
                    navController.navigate(Screen.Community.createRoute(item.sourceId, item.mangaId, item.slug, item.chapterUrl, item.commentId))
                }
            )
        }
        composable(Screen.ModerationDashboard.route) {
            ModerationDashboardScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.CommunityChat.route,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType; defaultValue = "global" },
                navArgument("title") { type = NavType.StringType; defaultValue = "الدردشة المباشرة" }
            )
        ) {
            CommunityChatScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route)    {
            SettingsScreen(
                onOpenDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onOpenCloudSync = { navController.navigate(Screen.CloudSync.route) }
            )
        }
        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.CloudSync.route) {
            CloudSyncScreen(onBack = { navController.popBackStack() })
        }
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
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) },
                onImportManga = { navController.navigate(Screen.ImportManga.route) }
            )
        }
        composable(Screen.ReadingStats.route) {
            ReadingStatsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Collections.route,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType; defaultValue = "" }
            )
        ) { back ->
            val collectionId = back.arguments?.getString("collectionId") ?: ""
            CollectionsScreen(
                onBack = { navController.popBackStack() },
                onCollectionClick = { id -> navController.navigate(Screen.Collections.createRoute(id)) }
            )
        }
        composable(Screen.Goals.route) {
            GoalsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.More.route) {
            MoreScreen(
                onOpenDownloads = { navController.navigate(Screen.Downloads.route) },
                onOpenLocalStorage = { navController.navigate(Screen.LocalStorage.route) },
                onOpenReadingStats = { navController.navigate(Screen.ReadingStats.route) },
                onOpenCollections = { navController.navigate(Screen.Collections.createRoute()) },
                onOpenGoals = { navController.navigate(Screen.Goals.route) },
                onOpenSources = { navController.navigate(Screen.Sources.route) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onOpenDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onOpenCloudSync = { navController.navigate(Screen.CloudSync.route) },
                onOpenSuggestions = { navController.navigate(Screen.Suggestions.route) },
                onOpenProfile = { navController.navigate(Screen.Profile.route) }
            )
        }
        composable(Screen.Sources.route) {
            SourcesScreen(
                onBack = { navController.popBackStack() },
                onSourceClick = { sourceId ->
                    navController.navigate(Screen.SourceBrowse.createRoute(sourceId))
                }
            )
        }
        composable(
            route = Screen.SourceBrowse.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType }
            )
        ) { back ->
            val sourceId = back.arguments?.getString("sourceId") ?: return@composable
            SourceBrowseScreen(
                sourceId = sourceId,
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ImportManga.route) {
            ImportMangaScreen(
                onBack = { navController.popBackStack() },
                onImportComplete = { /* Refresh local storage */ }
            )
        }
        composable(Screen.Suggestions.route) {
            SuggestionsScreen(
                onBack = { navController.popBackStack() },
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src.id, slug)) }
            )
        }
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginClick = { _, _ -> navController.navigate(Screen.Home.route) },
                onGoogleSignInClick = { navController.navigate(Screen.Home.route) },
                onForgotPasswordClick = { },
                onSignUpClick = { }
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
                onOpenCommunity = { mangaId ->
                    navController.navigate(Screen.Community.createRoute(sourceId, mangaId, slug))
                },
                onOpenChapterCommunity = { mangaId, chapterUrl ->
                    navController.navigate(Screen.Community.createRoute(sourceId, mangaId, slug, chapterUrl))
                },
                onOpenOtherSource = { otherSourceId, otherSlug ->
                    navController.navigate(Screen.Detail.createRoute(otherSourceId, otherSlug))
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
                chapterUrl = chapterUrl,
                onBack = { navController.popBackStack() },
                onOpenCommunity = {
                    navController.navigate(Screen.Community.createRoute(sourceId, mangaId, mangaId.substringAfter("${sourceId}_"), chapterUrl))
                }
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
                onBack = { navController.popBackStack() },
                onOpenCommunity = {
                    navController.navigate(Screen.Community.createRoute(sourceId, mangaId, mangaId.substringAfter("${sourceId}_"), chapterUrl))
                }
            )
        }
        composable(
            route = Screen.Community.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("mangaId") { type = NavType.StringType },
                navArgument("slug") { type = NavType.StringType },
                navArgument("chapterUrl") { type = NavType.StringType; nullable = true; defaultValue = "" },
                navArgument("commentId") { type = NavType.StringType; nullable = true; defaultValue = "" }
            )
        ) {
            val mangaId = it.arguments?.getString("mangaId") ?: "global"
            val slug = it.arguments?.getString("slug") ?: "الدردشة"
            CommunityScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { navController.navigate(Screen.CommunityChat.createRoute(mangaId, slug)) },
                onOpenProfile = { userId -> navController.navigate(Screen.PublicProfile.createRoute(userId)) }
            )
        }
    }
}
