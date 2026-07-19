import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

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
import com.exapps.mangaworld.presentation.localstorage.LocalMangaDetailScreen
import com.exapps.mangaworld.presentation.localstorage.ImportMangaScreen
import com.exapps.mangaworld.presentation.suggestions.SuggestionsScreen
import com.exapps.mangaworld.presentation.auth.login.LoginScreen
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import com.google.android.gms.auth.api.signin.GoogleSignInClient

sealed class Screen(val route: String) {
    object Home        : Screen("home")
    object Browse      : Screen("browse")
    object Search      : Screen("search")
    object Library     : Screen("library")
    object Settings    : Screen("settings")
    object ProfileSettings : Screen("profile_settings")
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
        fun createRoute(collectionId: String = "") = "collections/$collectionId"
    }
    object CollectionDetail : Screen("collection_detail/{collectionId}") {
        fun createRoute(collectionId: String) = "collection_detail/$collectionId"
    }
    object Goals : Screen("goals")
    object More : Screen("more")
    object Sources : Screen("sources")
    object SourceBrowse : Screen("source_browse/{sourceId}") {
        fun createRoute(sourceId: String) = "source_browse/$sourceId"
    }
    object LocalMangaDetail : Screen("local_manga_detail/{mangaId}") {
        fun createRoute(mangaId: String) = "local_manga_detail/$mangaId"
    }
    object ImportManga : Screen("import_manga")
    object Suggestions : Screen("suggestions")
    object Login : Screen("login")
    object SignUp : Screen("signup")
    object ForgotPassword : Screen("forgot_password")
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
    Triple(Screen.Home,         stringResource(R.string.home),     Icons.Filled.Home),
    Triple(Screen.Browse,       stringResource(R.string.browse),         Icons.Filled.GridView),
    Triple(Screen.Search,       stringResource(R.string.search),          Icons.Filled.Search),
    Triple(Screen.Library,      stringResource(R.string.library_section_title),      Icons.Filled.BookmarkBorder),
    Triple(Screen.More,         stringResource(R.string.more_title),       Icons.Filled.MoreHoriz),
)

@Composable
fun MangaNavGraph(
    navController: NavHostController,
    googleSignInClient: GoogleSignInClient,
    setFacebookCallbackManager: (com.facebook.CallbackManager) -> Unit
) {
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
                onOpenNotifications = { navController.navigate(Screen.Notifications.route) },
                onOpenLists = { navController.navigate(Screen.UserLists.route) },
                onOpenModeration = { navController.navigate(Screen.ModerationDashboard.route) },
                onOpenReadingStats = { navController.navigate(Screen.ReadingStats.route) },
                onOpenProfileSettings = { navController.navigate(Screen.ProfileSettings.route) },
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) }
            )
        }
        composable(Screen.UserLists.route) {
            UserListsScreen(
                onBack = { navController.popBackStack() },
                onItemClick = { sourceId, slug -> navController.navigate(Screen.Detail.createRoute(sourceId, slug)) }
            )
        }
        composable(Screen.PublicProfile.route, arguments = listOf(navArgument("userId") { type = NavType.StringType })) {
            PublicProfileScreen(
                onBack = { navController.popBackStack() },
                onItemClick = { sourceId, slug -> navController.navigate(Screen.Detail.createRoute(sourceId, slug)) }
            )
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
                navArgument("title") { type = NavType.StringType; defaultValue = stringResource(R.string.live_chat) }
            )
        ) { back ->
            // NavType.StringType does NOT auto-decode URL-encoded query params
            val roomId = java.net.URLDecoder.decode(back.arguments?.getString("roomId") ?: "global", "UTF-8")
            val title = java.net.URLDecoder.decode(back.arguments?.getString("title") ?: "", "UTF-8")
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
        composable(Screen.ProfileSettings.route) {
            com.exapps.mangaworld.presentation.profile.ProfileSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenReadingStats = { navController.navigate(Screen.ReadingStats.route) },
                onOpenCloudSync = { navController.navigate(Screen.CloudSync.route) },
                onOpenSources = { navController.navigate(Screen.Sources.route) },
                setFacebookCallbackManager = setFacebookCallbackManager
            )
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
                onMangaClick = { src, slug ->
                    if (MangaSource.isLocalSource(src)) {
                        // Imported manga → use the regular detail screen which loads from local disk
                        navController.navigate(Screen.Detail.createRoute("local", slug))
                    } else {
                        navController.navigate(Screen.Detail.createRoute(src, slug))
                    }
                },
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
        ) {
            CollectionsScreen(
                onBack = { navController.popBackStack() },
                onCollectionClick = { id -> navController.navigate(Screen.CollectionDetail.createRoute(id)) }
            )
        }
        composable(
            route = Screen.CollectionDetail.route,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType }
            )
        ) { back ->
            val collectionId = back.arguments?.getString("collectionId") ?: return@composable
            com.exapps.mangaworld.presentation.collections.CollectionDetailScreen(
                collectionId = collectionId,
                onBack = { navController.popBackStack() },
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) }
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
                onOpenGoals = { navController.navigate(Screen.Goals.route) },
                onOpenSources = { navController.navigate(Screen.Sources.route) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onOpenDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onOpenCloudSync = { navController.navigate(Screen.CloudSync.route) },
                onOpenSuggestions = { navController.navigate(Screen.Suggestions.route) },
                onOpenProfile = { navController.navigate(Screen.Profile.route) },
                onOpenModeration = { navController.navigate(Screen.ModerationDashboard.route) }
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
            ),
            deepLinks = listOf(navDeepLink { uriPattern = "mangaworld://screen/source_browse/{sourceId}" })
        ) { back ->
            val sourceId = back.arguments?.getString("sourceId") ?: return@composable
            SourceBrowseScreen(
                sourceId = sourceId,
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src, slug)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.LocalMangaDetail.route,
            arguments = listOf(
                navArgument("mangaId") { type = NavType.StringType }
            )
        ) { back ->
            val mangaId = back.arguments?.getString("mangaId") ?: return@composable
            LocalMangaDetailScreen(
                mangaId = mangaId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ImportManga.route) {
            ImportMangaScreen(
                onBack = { navController.popBackStack() },
                onImportComplete = { navController.popBackStack() }
            )
        }
        composable(Screen.Suggestions.route) {
            SuggestionsScreen(
                onBack = { navController.popBackStack() },
                onMangaClick = { src, slug -> navController.navigate(Screen.Detail.createRoute(src.id, slug)) }
            )
        }
        composable(Screen.Login.route) {
            val viewModel: com.exapps.mangaworld.presentation.auth.LoginViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current

            val googleLauncher = rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                try {
                    val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    val idToken = task.result?.idToken
                    if (idToken != null) {
                        viewModel.signInWithGoogleIdToken(idToken)
                    } else {
                        viewModel.clearError()
                    }
                } catch (_: Exception) {
                    viewModel.clearError() // user cancelled or error
                }
            }

            // Auto-navigate on successful sign-in
            LaunchedEffect(state.isSignedIn) {
                if (state.isSignedIn) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            }

            // Keep the SDK registration scoped to the login destination.
            val facebookCallbackManager = remember { com.facebook.CallbackManager.Factory.create() }
            DisposableEffect(facebookCallbackManager) {
                val loginManager = com.facebook.login.LoginManager.getInstance()
                val callback = object : com.facebook.FacebookCallback<com.facebook.login.LoginResult> {
                    override fun onSuccess(loginResult: com.facebook.login.LoginResult) {
                        viewModel.signInWithFacebook(loginResult.accessToken.token)
                    }
                    override fun onCancel() = Unit
                    override fun onError(error: com.facebook.FacebookException) {
                        viewModel.clearError()
                    }
                }
                setFacebookCallbackManager(facebookCallbackManager)
                loginManager.registerCallback(facebookCallbackManager, callback)
                onDispose { loginManager.unregisterCallback(facebookCallbackManager) }
            }

            LoginScreen(
                email = state.email,
                password = state.password,
                isLoading = state.isLoading,
                errorMessage = state.error,
                onEmailChanged = viewModel::onEmailChanged,
                onPasswordChanged = viewModel::onPasswordChanged,
                onLoginClick = viewModel::signInWithEmail,
                onGoogleSignInClick = {
                    googleLauncher.launch(googleSignInClient.signInIntent)
                },
                onFacebookLoginClick = {
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        com.facebook.login.LoginManager.getInstance().logInWithReadPermissions(
                            activity, listOf("email", "public_profile")
                        )
                    }
                },
                onForgotPasswordClick = { navController.navigate(Screen.ForgotPassword.route) },
                onSignUpClick = { navController.navigate(Screen.SignUp.route) }
            )
        }
        composable(Screen.SignUp.route) {
            val viewModel: com.exapps.mangaworld.presentation.auth.LoginViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val signUpContext = LocalContext.current

            val googleLauncher = rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                try {
                    val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    val idToken = task.result?.idToken
                    if (idToken != null) {
                        viewModel.signInWithGoogleIdToken(idToken)
                    } else {
                        viewModel.clearError()
                    }
                } catch (_: Exception) {
                    viewModel.clearError()
                }
            }

            // Auto-navigate on successful sign-in
            LaunchedEffect(state.isSignedIn) {
                if (state.isSignedIn) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            }

            // Keep the SDK registration scoped to the sign-up destination.
            val facebookCallbackManager = remember { com.facebook.CallbackManager.Factory.create() }
            DisposableEffect(facebookCallbackManager) {
                val loginManager = com.facebook.login.LoginManager.getInstance()
                val callback = object : com.facebook.FacebookCallback<com.facebook.login.LoginResult> {
                    override fun onSuccess(loginResult: com.facebook.login.LoginResult) {
                        viewModel.signInWithFacebook(loginResult.accessToken.token)
                    }
                    override fun onCancel() = Unit
                    override fun onError(error: com.facebook.FacebookException) {
                        viewModel.clearError()
                    }
                }
                setFacebookCallbackManager(facebookCallbackManager)
                loginManager.registerCallback(facebookCallbackManager, callback)
                onDispose { loginManager.unregisterCallback(facebookCallbackManager) }
            }

            com.exapps.mangaworld.presentation.auth.signup.SignUpScreen(
                onBack = { navController.popBackStack() },
                onSignUp = viewModel::signUpWithEmail,
                onGoogleSignInClick = { googleLauncher.launch(googleSignInClient.signInIntent) },
                onFacebookLoginClick = {
                    val activity = signUpContext as? android.app.Activity
                    if (activity != null) {
                        com.facebook.login.LoginManager.getInstance().logInWithReadPermissions(
                            activity, listOf("email", "public_profile")
                        )
                    }
                },
                isLoading = state.isLoading,
                error = state.error
            )
        }
        composable(Screen.ForgotPassword.route) {
            val viewModel: com.exapps.mangaworld.presentation.auth.LoginViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            com.exapps.mangaworld.presentation.auth.forgotpassword.ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
                isLoading = state.isLoading,
                error = state.error,
                onSendReset = viewModel::sendPasswordReset,
                passwordResetSent = state.passwordResetSent,
                onDismissSuccess = { viewModel.clearPasswordResetSent() }
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
            val source = MangaSource.fromIdOrNull(sourceId) ?: MangaSource.fromId(sourceId)
            MangaDetailScreen(
                source = source, slug = slug,
                rawSourceId = sourceId,
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
            val source = MangaSource.fromIdOrNull(sourceId) ?: MangaSource.fromId(sourceId)
            val isImported = mangaId.startsWith("imported_") || sourceId == "local"
            ReaderScreen(
                source = source, mangaId = mangaId,
                chapterUrl = chapterUrl,
                onBack = { navController.popBackStack() },
                onOpenCommunity = if (isImported) {{}} else {
                    { navController.navigate(Screen.Community.createRoute(sourceId, mangaId, mangaId.substringAfter("${sourceId}_"), chapterUrl)) }
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
            val chapterUrl = java.net.URLDecoder.decode(
                back.arguments?.getString("chapterUrl") ?: "", "UTF-8"
            )
            val source = MangaSource.fromIdOrNull(sourceId) ?: return@composable
            val isImported = mangaId.startsWith("imported_") || sourceId == "local"
            ReaderScreen(
                source = source,
                mangaId = mangaId,
                chapterUrl = chapterUrl,
                onBack = { navController.popBackStack() },
                onOpenCommunity = if (isImported) {{}} else {
                    { navController.navigate(Screen.Community.createRoute(sourceId, mangaId, mangaId.substringAfter("${sourceId}_"), chapterUrl)) }
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
            val slug = it.arguments?.getString("slug") ?: stringResource(R.string.chat)
            CommunityScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { navController.navigate(Screen.CommunityChat.createRoute(mangaId, slug)) },
                onOpenProfile = { userId -> navController.navigate(Screen.PublicProfile.createRoute(userId)) }
            )
        }
    }
}
