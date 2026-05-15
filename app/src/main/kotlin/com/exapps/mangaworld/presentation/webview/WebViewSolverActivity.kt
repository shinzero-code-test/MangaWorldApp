package com.exapps.mangaworld.presentation.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.theme.MangaWorldTheme

/**
 * WebView activity for bypassing Cloudflare verification on sites like Olympus
 * Launched when a scraper detects a CF challenge (403 / "Just a moment" page)
 */
class WebViewSolverActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_DOMAIN = "extra_domain"
        const val RESULT_COOKIES = "result_cookies"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: "olympustaff.com"

        setContent {
            MangaWorldTheme(darkTheme = true) {
                CloudflareWebView(
                    url = url,
                    domain = domain,
                    onVerified = { cookies ->
                        val result = Intent()
                            .putExtra(RESULT_COOKIES, cookies)
                            .putExtra(EXTRA_DOMAIN, domain)
                        setResult(RESULT_OK, result)
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CloudflareWebView(
    url: String,
    domain: String,
    onVerified: (String) -> Unit,
    onClose: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(MangaColors.Surface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "إغلاق", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("تحقق من الهوية", style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurface, modifier = Modifier.weight(1f))
        }
        if (isLoading) {
            LinearProgressIndicator(
                progress = { loadingProgress },
                modifier = Modifier.fillMaxWidth(),
                color = MangaColors.Primary,
                trackColor = MangaColors.SurfaceContainer
            )
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        allowContentAccess = true
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            isLoading = false
                            // Check if we passed Cloudflare (no longer on CF challenge page)
                            val title = view?.title ?: ""
                            val isCfChallenge = title.contains("Just a moment") ||
                                title.contains("Attention Required")
                            if (!isCfChallenge && pageUrl?.contains(domain) == true) {
                                val cookies = CookieManager.getInstance().getCookie(pageUrl) ?: ""
                                if (cookies.contains("cf_clearance")) {
                                    onVerified(cookies)
                                }
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadingProgress = newProgress / 100f
                            isLoading = newProgress < 100
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
