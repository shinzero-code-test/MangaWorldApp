package com.exapps.mangaworld.presentation.auth.forgotpassword

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exapps.mangaworld.presentation.auth.login.MangaTextField
import com.exapps.mangaworld.presentation.theme.MangaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onResetSent: () -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    onSendReset: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("استعادة كلمة المرور", color = MangaColors.OnSurface) },
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
            Spacer(Modifier.height(48.dp))

            Icon(
                Icons.Filled.Email, null,
                tint = MangaColors.Primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(20.dp))

            Text(
                "أدخل بريدك الإلكتروني وسنرسل لك رابطاً لإعادة تعيين كلمة المرور",
                style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            MangaTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "البريد الإلكتروني",
                leadingIcon = Icons.Filled.Email,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    keyboardController?.hide()
                    if (email.isNotBlank()) onSendReset(email.trim())
                })
            )
            Spacer(Modifier.height(16.dp))

            if (error != null) {
                Text(error, color = MangaColors.Primary, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    keyboardController?.hide()
                    if (email.isNotBlank()) {
                        onSendReset(email.trim())
                        sent = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Primary),
                enabled = !isLoading && email.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    Text("إرسال رابط الاستعادة", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (sent && error == null) {
                Spacer(Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "تم إرسال رابط إعادة تعيين كلمة المرور إلى \"$email\".\nتحقق من صندوق الوارد.",
                        modifier = Modifier.padding(16.dp),
                        color = MangaColors.OnSurface,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
