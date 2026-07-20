package com.exapps.mangaworld.presentation.webview

import android.content.Context
import com.exapps.mangaworld.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.exapps.mangaworld.core.data.remote.scraper.BaseScraperImpl
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.theme.MangaWorldTheme

class WebViewSolverActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL    = "extra_url"
        const val EXTRA_DOMAIN = "extra_domain"
        const val RESULT_COOKIES = "result_cookies"

        private val ALLOWED_DOMAINS = MangaSource.entries.map {
            java.net.URI(it.baseUrl).host
        }.toSet()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url    = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: run { finish(); return }

        if (domain !in ALLOWED_DOMAINS || !url.startsWith("https://")) {
            finish()
            return
        }

        // Clear old cookies for this domain so the WebView solver
        // doesn't immediately close thinking the old cf_clearance is still valid
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.getCookie("https://$domain")?.let { oldCookies ->
            // Remove each old cookie individually
            oldCookies.split(";").forEach { c ->
                val name = c.trim().substringBefore("=")
                if (name.isNotBlank()) {
                    cm.setCookie("https://$domain", "$name=; Max-Age=0; path=/")
                }
            }
            cm.flush()
        }

        setContent {
            MangaWorldTheme(darkTheme = true) {
                CloudflareWebView(
                    url = url,
                    domain = domain,
                    onVerified = { cookies ->
                        setResult(RESULT_OK,
                            Intent().putExtra(RESULT_COOKIES, cookies)
                                    .putExtra(EXTRA_DOMAIN, domain))
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
    var progress  by remember { mutableFloatStateOf(0f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {

        Row(
            Modifier.fillMaxWidth().background(MangaColors.Surface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, LocalContext.current.getString(R.string.close), tint = Color.White)
            }
            Text(stringResource(R.string.fmt_067, domain),
                style = MaterialTheme.typography.bodyMedium,
                color = MangaColors.OnSurface,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(Icons.Filled.Refresh, LocalContext.current.getString(R.string.reload_alt), tint = MangaColors.Cyan)
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MangaColors.Primary,
                trackColor = MangaColors.SurfaceContainer
            )
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    webViewRef = wv
                    wv.settings.apply {
                        javaScriptEnabled  = true
                        domStorageEnabled  = true
                        databaseEnabled    = true
                        userAgentString    = BaseScraperImpl.USER_AGENT
                        loadWithOverviewMode = true
                        useWideViewPort    = true
                        allowContentAccess = false
                        setSupportZoom(true)
                    }

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, false)

                    // NOTE: Do NOT call removeAllCookies here — the targeted
                    // domain-only clearing in onCreate is sufficient and prevents
                    // wiping cookies from other sources.

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            if (pageUrl == null) return
                            val title = view?.title ?: ""
                            val isCfPage = title.contains("Just a moment", ignoreCase = true) ||
                                           title.contains("Attention Required", ignoreCase = true)
                            if (!isCfPage && pageUrl.contains(domain)) {
                                val cookies = cm.getCookie(pageUrl) ?: ""
                                if (cookies.contains("cf_clearance")) {
                                    cm.flush()
                                    onVerified(cookies)
                                }
                            }
                        }

                        @Deprecated("Needed for API < 23")
                        override fun onReceivedError(
                            view: WebView?, errorCode: Int,
                            description: String?, failingUrl: String?
                        ) { /* ignore: Cloudflare pages intentionally return errors */ }
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress  = newProgress / 100f
                            isLoading = newProgress < 100
                        }
                    }

                    wv.loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
