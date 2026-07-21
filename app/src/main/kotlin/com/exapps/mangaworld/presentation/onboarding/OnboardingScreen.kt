package com.exapps.mangaworld.presentation.onboarding

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.presentation.components.GradientButton
import com.exapps.mangaworld.presentation.theme.rememberDominantColor
import com.exapps.mangaworld.presentation.theme.MangaColors
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: ImageVector,
    val iconTint: Color,
    val iconBg: Color,
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val subtitleRes: Int? = null,
    val subtitleText: String? = null
)

val onboardingPages = listOf(
    OnboardingPage(
        icon = Icons.Filled.MenuBook,
        iconTint = MangaColors.PrimaryLight,
        iconBg = MangaColors.GlowPurple,
        titleRes = R.string.onboarding_read_title,
        subtitleRes = R.string.str_010
    ),
    OnboardingPage(
        icon = Icons.Filled.Language,
        iconTint = MangaColors.Cyan,
        iconBg = MangaColors.GlowCyan,
        titleRes = R.string.onboarding_sources_title,
        subtitleText = "Olympus Staff · Azora Moon · Manga Starz\nManga Sid · Meshmanga"
    ),
    OnboardingPage(
        icon = Icons.Filled.Download,
        iconTint = MangaColors.Green,
        iconBg = Color(0x2244BB44),
        titleRes = R.string.onboarding_offline_title,
        subtitleRes = R.string.download_favorites_offline
    ),
    OnboardingPage(
        icon = Icons.Filled.Tune,
        iconTint = MangaColors.Yellow,
        iconBg = Color(0x22FFDD00),
        titleRes = R.string.onboarding_custom_title,
        subtitleRes = R.string.str_443
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState { onboardingPages.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == onboardingPages.size - 1
    val dominant = rememberDominantColor(R.mipmap.ic_launcher)

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(dominant ?: MangaColors.Background, Color(0xFF0D0D1A)))
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            // Skip
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End) {
                AnimatedVisibility(!isLast) {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.onboarding_skip), color = MangaColors.Muted,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                OnboardingPageContent(onboardingPages[page])
            }

            // Dots + navigation
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Dots
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(onboardingPages.size) { i ->
                        val active = i == pagerState.currentPage
                        Box(
                            Modifier
                                .size(if (active) 24.dp else 8.dp, 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (active) MangaColors.Primary else MangaColors.OutlineVariant
                                )
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                // Button
                GradientButton(
                    text = if (isLast) stringResource(R.string.onboarding_start) else stringResource(R.string.next),
                    onClick = {
                        if (isLast) onFinish()
                        else scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with glow
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(page.iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(page.icon, null, tint = page.iconTint, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MangaColors.OnSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            page.subtitleText ?: stringResource(page.subtitleRes!!),
            style = MaterialTheme.typography.bodyLarge,
            color = MangaColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )
    }
}
