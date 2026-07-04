package com.exapps.mangaworld.presentation.auth.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exapps.mangaworld.R
import com.exapps.mangaworld.presentation.theme.MangaColors

@Composable
fun LoginScreen(
    email: String = "",
    password: String = "",
    onEmailChanged: (String) -> Unit = {},
    onPasswordChanged: (String) -> Unit = {},
    onLoginClick: (email: String, password: String) -> Unit,
    onGoogleSignInClick: () -> Unit,
    onFacebookLoginClick: () -> Unit = {},
    onForgotPasswordClick: () -> Unit,
    onSignUpClick: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MangaColors.Background)
        ) {
            BackgroundDecor(modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                // App Logo
                MangaWorldLogo()
                Spacer(modifier = Modifier.height(20.dp))

                Row {
                    Text("Manga", color = MangaColors.OnSurface, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text("World", color = MangaColors.Primary, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("عالمك. مانغاك.", color = MangaColors.OnSurfaceVariant, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(36.dp))

                // Email field
                MangaTextField(
                    value = email,
                    onValueChange = onEmailChanged,
                    placeholder = "البريد الإلكتروني أو اسم المستخدم",
                    leadingIcon = Icons.Filled.Email,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Password field
                MangaTextField(
                    value = password,
                    onValueChange = onPasswordChanged,
                    placeholder = "كلمة المرور",
                    leadingIcon = Icons.Filled.Lock,
                    isPassword = true,
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = {
                        keyboardController?.hide()
                        onLoginClick(email, password)
                    })
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage, color = MangaColors.Primary, fontSize = 12.sp, textAlign = TextAlign.Center)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "نسيت كلمة المرور؟",
                        color = MangaColors.Primary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onForgotPasswordClick() }
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))

                // Login button
                Button(
                    onClick = {
                        keyboardController?.hide()
                        onLoginClick(email, password)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Primary),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Text("تسجيل الدخول", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Divider
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MangaColors.SurfaceContainer)
                    Text("  أو  ", color = MangaColors.OnSurfaceVariant, fontSize = 13.sp)
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MangaColors.SurfaceContainer)
                }
                Spacer(modifier = Modifier.height(24.dp))

                // Google Sign-In button
                OutlinedButton(
                    onClick = onGoogleSignInClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
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
                    Text("متابعة باستخدام Google", color = MangaColors.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Facebook Login button
                OutlinedButton(
                    onClick = onFacebookLoginClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1877F2))
                ) {
                    Text("f", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("متابعة باستخدام Facebook", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(28.dp))
                Row {
                    Text("ليس لديك حساب؟ ", color = MangaColors.OnSurfaceVariant, fontSize = 13.sp)
                    Text(
                        "سجل الآن",
                        color = MangaColors.Primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onSignUpClick() }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BackgroundDecor(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        repeat(6) { i ->
            val radius = size.minDimension * (0.10f + i * 0.022f)
            drawCircle(
                color = MangaColors.Primary.copy(alpha = 0.10f - i * 0.012f),
                radius = radius,
                center = Offset(
                    x = radius * 0.5f + i * 24f,
                    y = size.height - radius * 0.5f - i * 32f
                )
            )
        }
    }
}

@Composable
private fun MangaWorldLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(MangaColors.Surface)
            .border(
                width = 3.dp,
                brush = Brush.sweepGradient(
                    listOf(MangaColors.Primary, MangaColors.Primary.copy(alpha = 0.6f), MangaColors.Primary)
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = R.drawable.mangaworld_logo),
            contentDescription = "MangaWorld",
            modifier = Modifier.size(72.dp)
        )
    }
}

@Composable
fun MangaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MangaColors.Surface)
            .border(1.dp, MangaColors.SurfaceContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(leadingIcon, contentDescription = null, tint = MangaColors.Primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = MangaColors.OnSurface, fontSize = 15.sp, textAlign = TextAlign.End),
                visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = keyboardActions,
                cursorBrush = SolidColor(MangaColors.Primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = MangaColors.OnSurfaceVariant,
                                fontSize = 15.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        if (isPassword && onTogglePasswordVisibility != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (passwordVisible) "إخفاء" else "إظهار",
                tint = MangaColors.OnSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onTogglePasswordVisibility() }
            )
        }
    }
}
