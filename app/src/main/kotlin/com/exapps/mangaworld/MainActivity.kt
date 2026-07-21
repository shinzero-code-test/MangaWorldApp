package com.exapps.mangaworld
import com.exapps.mangaworld.R

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.model.AppTheme
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.navigation.*
import com.exapps.mangaworld.presentation.onboarding.OnboardingScreen
import com.exapps.mangaworld.presentation.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import dagger.hilt.android.AndroidEntryPoint
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var sessionManager: FirebaseSessionManager
    private val deepLinkIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    // Facebook login callback manager — set by the login composable
    private var facebookCallbackManager: com.facebook.CallbackManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermissionsIfNeeded()

        splash.setKeepOnScreenCondition { false }

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MangaApp(
                    sessionManager = sessionManager,
                    settingsRepo = settingsRepository,
                    launchIntent = intent,
                    deepLinkIntents = deepLinkIntents.asSharedFlow(),
                    setFacebookCallbackManager = { facebookCallbackManager = it }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntents.tryEmit(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        facebookCallbackManager?.onActivityResult(requestCode, resultCode, data)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            android.util.Log.w("MainActivity", "Permissions denied: $denied")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.util.Log.i("MainActivity", "POST_NOTIFICATIONS permission denied — notifications will not be shown")
        }
    }

    private fun requestStoragePermissionsIfNeeded() {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = base.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            storagePermissionLauncher.launch(missing.toTypedArray())
        }

        // Request notification permission separately (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPermission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, notifPermission) != PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(notifPermission)) {
                    // User previously denied — we can request again but OS may auto-deny
                    notificationPermissionLauncher.launch(notifPermission)
                } else {
                    notificationPermissionLauncher.launch(notifPermission)
                }
            }
        }
    }
}

@Composable
private fun MangaApp(
    sessionManager: FirebaseSessionManager,
    settingsRepo: SettingsRepository,
    launchIntent: Intent?,
    deepLinkIntents: kotlinx.coroutines.flow.Flow<Intent>,
    setFacebookCallbackManager: (com.facebook.CallbackManager) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by settingsRepo.getAppSettings().collectAsStateWithLifecycle(
        initialValue = com.exapps.mangaworld.domain.model.AppSettings()
    )
    val isDark = when (settings.theme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    // After onboarding completes, show login screen
    var showPostOnboardingLogin by rememberSaveable { mutableStateOf(false) }

    // Check if user is logged in (not anonymous)
    val firebaseUser by sessionManager.authState.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentUser()
    )
    val userIsLoggedIn = firebaseUser?.isAnonymous == false
    val googleSignInClient = remember(sessionManager) { sessionManager.googleSignInClient() }

    // Show login screen on first launch if not logged in
    LaunchedEffect(settings.onboardingCompleted) {
        if (settings.onboardingCompleted && !userIsLoggedIn) {
            showPostOnboardingLogin = true
        }
    }

    val biometricSupported = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    var isLocked by rememberSaveable(settings.biometricLockEnabled) {
        mutableStateOf(settings.biometricLockEnabled && biometricSupported)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(settings.biometricLockEnabled, biometricSupported) {
        if (!settings.biometricLockEnabled || !biometricSupported) {
            isLocked = false
        }
    }

    DisposableEffect(lifecycleOwner, settings.biometricLockEnabled, biometricSupported) {
        val observer = LifecycleEventObserver { _, event ->
            if (settings.biometricLockEnabled && biometricSupported && event == Lifecycle.Event.ON_STOP) {
                isLocked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MangaWorldTheme(darkTheme = isDark, useDynamicColors = settings.useDynamicColors) {
        Box(Modifier.fillMaxSize()) {
            when {
                showPostOnboardingLogin -> {
                    val loginViewModel: com.exapps.mangaworld.presentation.auth.LoginViewModel = hiltViewModel()
                    val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()

                    // Sub-screen state: "login", "signup", "forgot"
                    var postOnboardingScreen by rememberSaveable { mutableStateOf("login") }

                    // Auto-dismiss when signed in
                    LaunchedEffect(loginState.isSignedIn) {
                        if (loginState.isSignedIn) {
                            showPostOnboardingLogin = false
                        }
                    }

                    val googleLauncher = rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        try {
                            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
                            val idToken = task.result?.idToken
                            if (idToken != null) {
                                loginViewModel.signInWithGoogleIdToken(idToken)
                            } else {
                                loginViewModel.clearError()
                            }
                        } catch (_: Exception) {
                            loginViewModel.clearError() // user cancelled or error
                        }
                    }

                    // Keep the SDK registration scoped to the displayed login flow.
                    val facebookCallbackManager = remember { com.facebook.CallbackManager.Factory.create() }
                    DisposableEffect(facebookCallbackManager) {
                        val loginManager = com.facebook.login.LoginManager.getInstance()
                        val callback = object : com.facebook.FacebookCallback<com.facebook.login.LoginResult> {
                            override fun onSuccess(result: com.facebook.login.LoginResult) {
                                loginViewModel.signInWithFacebook(result.accessToken.token)
                            }
                            override fun onCancel() = Unit
                            override fun onError(error: com.facebook.FacebookException) {
                                loginViewModel.clearError()
                            }
                        }
                        setFacebookCallbackManager(facebookCallbackManager)
                        loginManager.registerCallback(facebookCallbackManager, callback)
                        onDispose { loginManager.unregisterCallback(facebookCallbackManager) }
                    }

                    when (postOnboardingScreen) {
                        "signup" -> {
                            // System back from signup → go back to login
                            androidx.activity.compose.BackHandler {
                                postOnboardingScreen = "login"
                            }
                            com.exapps.mangaworld.presentation.auth.signup.SignUpScreen(
                                onBack = { postOnboardingScreen = "login" },
                                onSignUp = loginViewModel::signUpWithEmail,
                                onGoogleSignInClick = { googleLauncher.launch(googleSignInClient.signInIntent) },
                                onFacebookLoginClick = {
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        com.facebook.login.LoginManager.getInstance().logInWithReadPermissions(
                                            activity, listOf("email", "public_profile")
                                        )
                                    }
                                },
                                isLoading = loginState.isLoading,
                                error = loginState.error
                            )
                        }
                        "forgot" -> {
                            // System back from forgot → go back to login
                            androidx.activity.compose.BackHandler {
                                postOnboardingScreen = "login"
                            }
                            com.exapps.mangaworld.presentation.auth.forgotpassword.ForgotPasswordScreen(
                                onBack = { postOnboardingScreen = "login" },
                                isLoading = loginState.isLoading,
                                error = loginState.error,
                                onSendReset = loginViewModel::sendPasswordReset,
                                passwordResetSent = loginState.passwordResetSent,
                                onDismissSuccess = { loginViewModel.clearPasswordResetSent() }
                            )
                        }
                        else -> {
                            // System back from login → dismiss overlay, proceed as guest
                            androidx.activity.compose.BackHandler {
                                showPostOnboardingLogin = false
                            }
                            com.exapps.mangaworld.presentation.auth.login.LoginScreen(
                                email = loginState.email,
                                password = loginState.password,
                                isLoading = loginState.isLoading,
                                errorMessage = loginState.error,
                                onEmailChanged = loginViewModel::onEmailChanged,
                                onPasswordChanged = loginViewModel::onPasswordChanged,
                                onLoginClick = loginViewModel::signInWithEmail,
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
                                onForgotPasswordClick = { postOnboardingScreen = "forgot" },
                                onSignUpClick = { postOnboardingScreen = "signup" }
                            )
                        }
                    }

                    // Dismiss button — proceed without login
                    TextButton(
                        onClick = { showPostOnboardingLogin = false },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    ) {
                        Text(LocalContext.current.getString(R.string.skip_guest_login), color = MangaColors.Muted, fontSize = 14.sp)
                    }
                }
                !settings.onboardingCompleted -> {
                    OnboardingScreen(
                        onFinish = {
                            scope.launch {
                                settingsRepo.setOnboardingCompleted(true)
                                showPostOnboardingLogin = true
                            }
                        }
                    )
                }
                else -> {
                    MangaWorldContent(
                        launchIntent = launchIntent,
                        deepLinkIntents = deepLinkIntents,
                        googleSignInClient = googleSignInClient,
                        setFacebookCallbackManager = setFacebookCallbackManager
                    )
                }
            }

            if (settings.biometricLockEnabled && biometricSupported && isLocked) {
                BiometricLockOverlay(onUnlocked = { isLocked = false })
            }
        }
    }
}

@Composable
private fun MangaWorldContent(
    launchIntent: Intent?,
    deepLinkIntents: kotlinx.coroutines.flow.Flow<Intent>,
    googleSignInClient: GoogleSignInClient,
    setFacebookCallbackManager: (com.facebook.CallbackManager) -> Unit
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
        Screen.Library.route, Screen.More.route, Screen.Downloads.route,
        Screen.LocalStorage.route, Screen.Profile.route, Screen.Settings.route
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
            MangaNavGraph(
                navController = navController,
                googleSignInClient = googleSignInClient,
                setFacebookCallbackManager = setFacebookCallbackManager
            )
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
                        Icon(icon, contentDescription = stringResource(label),
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

@Composable
private fun BiometricLockOverlay(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun launchPrompt() {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    errorMessage = null
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        errorMessage = errString.toString()
                    }
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.open_mangaworld))
            .setSubtitle(context.getString(R.string.use_biometric_continue))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) { launchPrompt() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE09090E))
            .padding(24.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MangaColors.Surface)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(context.current.getString(R.string.app_locked), style = MaterialTheme.typography.titleLarge, color = MangaColors.OnSurface)
                Text(
                    errorMessage ?: context.current.getString(R.string.unlock_app_biometric),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MangaColors.OnSurfaceVariant
                )
                Button(onClick = ::launchPrompt) { Text("إعادة المحاولة") }
            }
        }
    }
}
