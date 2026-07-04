package com.exapps.mangaworld.presentation.auth.signup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exapps.mangaworld.presentation.auth.login.MangaTextField
import com.exapps.mangaworld.presentation.theme.MangaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    onBack: () -> Unit,
    onSignUp: (email: String, password: String) -> Unit,
    isLoading: Boolean = false,
    error: String? = null
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("إنشاء حساب", color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
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
                "أنشئ حسابك وابدأ القراءة",
                style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

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
                placeholder = "كلمة المرور (6 أحرف على الأقل)",
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
                placeholder = "تأكيد كلمة المرور",
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

            Button(
                onClick = {
                    keyboardController?.hide()
                    onSignUp(email.trim(), password)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Primary),
                enabled = !isLoading && email.isNotBlank() && password.length >= 6 && password == confirmPassword
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    Text("إنشاء حساب", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
