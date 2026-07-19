package com.exapps.mangaworld.presentation.auth.signup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.presentation.auth.login.MangaTextField
import com.exapps.mangaworld.presentation.theme.MangaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    onBack: () -> Unit,
    onSignUp: (email: String, password: String, displayName: String, username: String) -> Unit,
    onGoogleSignInClick: () -> Unit = {},
    onFacebookLoginClick: () -> Unit = {},
    isLoading: Boolean = false,
    error: String? = null
) {
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Username validation state
    val normalizedUsername = username.trim().lowercase()
    val usernameError = when {
        normalizedUsername.isEmpty() -> null
        normalizedUsername.length < 3 -> "اسم المستخدم قصير جداً (3 أحرف على الأقل)"
        normalizedUsername.length > 20 -> "اسم المستخدم طويل جداً (20 حرف كحد أقصى)"
        !normalizedUsername.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$")) -> "أحرف وأرقام وشرطات سفلية فقط"
        else -> null
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auth_signup), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.auth_welcome),
                style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            // ── Social Sign-Up Buttons ──────────────────────────────────────

            OutlinedButton(
                onClick = onGoogleSignInClick,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MangaColors.Surface)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_google),
                    contentDescription = "Google",
                    modifier = Modifier.size(18.dp),
                    tint = Color.Unspecified
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.auth_google), color = MangaColors.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onFacebookLoginClick,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1877F2))
            ) {
                Text("f", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.auth_facebook), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Divider ─────────────────────────────────────────────────────

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MangaColors.SurfaceContainer)
                Text("  أو  ", color = MangaColors.OnSurfaceVariant, fontSize = 13.sp)
                HorizontalDivider(modifier = Modifier.weight(1f), color = MangaColors.SurfaceContainer)
            }
            Spacer(modifier = Modifier.height(20.dp))

            // ── Display Name Field ───────────────────────────────────────────

            MangaTextField(
                value = displayName,
                onValueChange = { displayName = it },
                placeholder = stringResource(R.string.profile_display_name),
                leadingIcon = Icons.Filled.Person,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            )
            Spacer(Modifier.height(12.dp))

            // ── Username Field ───────────────────────────────────────────────

            MangaTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.profile_username),
                leadingIcon = Icons.Filled.Badge,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            )
            if (usernameError != null) {
                Spacer(Modifier.height(4.dp))
                Text(usernameError, color = MangaColors.Yellow, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))

            // ── Email / Password Fields ─────────────────────────────────────

            MangaTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "البريد الإلكتروني",
                leadingIcon = Icons.Filled.Email,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )
            Spacer(Modifier.height(12.dp))

            MangaTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.auth_password_hint),
                leadingIcon = Icons.Filled.Lock,
                isPassword = true,
                passwordVisible = passwordVisible,
                onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            )
            Spacer(Modifier.height(12.dp))

            MangaTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                placeholder = stringResource(R.string.auth_confirm_password),
                leadingIcon = Icons.Filled.Lock,
                isPassword = true,
                passwordVisible = passwordVisible,
                onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MangaColors.Primary, fontSize = 13.sp)
            }

            if (password.isNotBlank() && confirmPassword.isNotBlank() && password != confirmPassword) {
                Spacer(Modifier.height(4.dp))
                Text("كلمتا المرور غير متطابقتين", color = MangaColors.Yellow, fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))

            val canSubmit = !isLoading &&
                email.isNotBlank() && password.isNotBlank() && password == confirmPassword &&
                displayName.isNotBlank() && username.isNotBlank() && usernameError == null

            Button(
                onClick = {
                    keyboardController?.hide()
                    onSignUp(email.trim(), password, displayName.trim(), normalizedUsername)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Primary),
                enabled = canSubmit
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    Text(stringResource(R.string.auth_signup), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
