package com.exapps.mangaworld

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.exapps.mangaworld.domain.model.AppTheme
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.navigation.*
import com.exapps.mangaworld.presentation.onboarding.OnboardingScreen
import com.exapps.mangaworld.presentation.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    private val deepLinkIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermissionsIfNeeded()

        splash.setKeepOnScreenCondition { false }

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MangaApp(settingsRepository, intent, deepLinkIntents.asSharedFlow())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntents.tryEmit(intent)
    }

    private fun requestStoragePermissionsIfNeeded() {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = base.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
            .launch(missing.toTypedArray())
    }
}

@Composable
private fun MangaApp(
    settingsRepo: SettingsRepository,
    launchIntent: Intent?,
    deepLinkIntents: kotlinx.coroutines.flow.Flow<Intent>
) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.getAppSettings().collectAsStateWithLifecycle(
        initialValue = com.exapps.mangaworld.domain.model.AppSettings()
    )
    val isDark = when (settings.theme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    MangaWorldTheme(darkTheme = isDark) {
        if (!settings.onboardingCompleted) {
            OnboardingScreen(
                onFinish = {
                    scope.launch {
                        settingsRepo.setOnboardingCompleted(true)
                    }
                }
            )
        } else {
            MangaWorldContent(launchIntent = launchIntent, deepLinkIntents = deepLinkIntents)
        }
    }
}

@Composable
private fun MangaWorldContent(
    launchIntent: Intent?,
    deepLinkIntents: kotlinx.coroutines.flow.Flow<Intent>
) {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination

    LaunchedEffect(navController, launchIntent) {
        launchIntent?.let { navController.handleDeepLink(it) }
    }

    LaunchedEffect(navController, deepLinkIntents) {
        deepLinkIntents.collect { intent ->
            navController.handleDeepLink(intent)
        }
    }

    // Only show bottom bar on top-level routes
    val topLevelRoutes = setOf(
        Screen.Home.route, Screen.Browse.route, Screen.Search.route,
        Screen.Library.route, Screen.Downloads.route,
        Screen.LocalStorage.route, Screen.Settings.route
    )
    val showBottomBar = currentDest?.route in topLevelRoutes

    Scaffold(
        containerColor = MangaColors.Background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                MangaBottomBar(
                    currentRoute = currentDest?.route,
                    onNavigate = { screen ->
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = if (showBottomBar) paddingValues.calculateBottomPadding() else 0.dp)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            MangaNavGraph(navController = navController)
        }
    }
}

@Composable
private fun MangaBottomBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar(
        containerColor = MangaColors.Surface,
        tonalElevation = 0.dp,
        modifier = Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        bottomNavItems.forEach { item ->
            val (screen, label, icon) = item
            val selected = currentRoute == screen.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screen) },
                icon = {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        if (selected) {
                            Box(
                                Modifier
                                    .size(36.dp, 28.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MangaColors.GlowPurple)
                            )
                        }
                        Icon(icon, contentDescription = label,
                            modifier = Modifier.size(22.dp))
                    }
                },
                label = {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MangaColors.PrimaryLight,
                    selectedTextColor = MangaColors.PrimaryLight,
                    unselectedIconColor = MangaColors.Muted,
                    unselectedTextColor = MangaColors.Muted,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
