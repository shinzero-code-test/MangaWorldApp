import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

package com.exapps.mangaworld.presentation.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.*
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val achievementManager: AchievementManager
) : ViewModel() {

    val goals = achievementManager.goals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val achievements = achievementManager.achievements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalPagesRead = achievementManager.totalPagesRead
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalChaptersRead = achievementManager.totalChaptersRead
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun createGoal(type: GoalType, targetValue: Int, period: GoalPeriod) {
        viewModelScope.launch {
            achievementManager.createGoal(type, targetValue, period)
        }
    }

    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            achievementManager.deleteGoal(goalId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    viewModel: GoalsViewModel = hiltViewModel()
) {
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val achievements by viewModel.achievements.collectAsStateWithLifecycle()
    val totalPages by viewModel.totalPagesRead.collectAsStateWithLifecycle()
    val totalChapters by viewModel.totalChaptersRead.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.goals_and_achievements), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Stats overview
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.total_pages),
                        value = "$totalPages",
                        emoji = "📖"
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.total_chapters),
                        value = "$totalChapters",
                        emoji = "📚"
                    )
                }
            }

            // Goals section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.active_goals),
                        style = MaterialTheme.typography.titleMedium,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.add_goal), tint = MangaColors.Cyan)
                    }
                }
            }

            if (goals.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.str_357),
                            modifier = Modifier.padding(16.dp),
                            color = MangaColors.OnSurfaceVariant
                        )
                    }
                }
            } else {
                items(goals) { goal ->
                    GoalCard(
                        goal = goal,
                        onDelete = { viewModel.deleteGoal(goal.id) }
                    )
                }
            }

            // Achievements section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.more_goals),
                    style = MaterialTheme.typography.titleMedium,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold
                )
            }

            items(achievements) { achievement ->
                AchievementCard(achievement)
            }
        }
    }

    if (showCreateDialog) {
        CreateGoalDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { type, target, period ->
                viewModel.createGoal(type, target, period)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    emoji: String
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MangaColors.OnSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GoalCard(
    goal: ReadingGoal,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        goal.type.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.fmt_064, goal.targetValue, goal.period.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.OnSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = MangaColors.Error)
                }
            }

            LinearProgressIndicator(
                progress = { goal.progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (goal.isCompleted) MangaColors.Cyan else MangaColors.Primary,
                trackColor = MangaColors.Muted.copy(alpha = 0.3f)
            )

            Text(
                "${goal.currentValue} / ${goal.targetValue} (${(goal.progressPercent * 100).toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MangaColors.OnSurfaceVariant
            )
        }
    }
}

@Composable
private fun AchievementCard(achievement: Achievement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (achievement.isUnlocked) MangaColors.Surface else MangaColors.Surface.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (achievement.isUnlocked) MangaColors.Cyan.copy(alpha = 0.2f)
                        else MangaColors.Muted.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    achievement.icon,
                    fontSize = 24.sp,
                    color = if (achievement.isUnlocked) MangaColors.OnSurface else MangaColors.Muted
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    achievement.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (achievement.isUnlocked) MangaColors.OnSurface else MangaColors.Muted,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    achievement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.OnSurfaceVariant
                )
            }
            if (achievement.isUnlocked) {
                Text("✓", color = MangaColors.Cyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CreateGoalDialog(
    onDismiss: () -> Unit,
    onCreate: (GoalType, Int, GoalPeriod) -> Unit
) {
    var selectedType by remember { mutableStateOf(GoalType.PAGES_READ) }
    var targetValue by remember { mutableStateOf("10") }
    var selectedPeriod by remember { mutableStateOf(GoalPeriod.DAILY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_new_goal)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.str_430), color = MangaColors.OnSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = targetValue,
                    onValueChange = { targetValue = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.target_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.str_168), color = MangaColors.OnSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(period.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    targetValue.toIntOrNull()?.let { target ->
                        onCreate(selectedType, target, selectedPeriod)
                    }
                },
                enabled = targetValue.toIntOrNull() != null && (targetValue.toIntOrNull() ?: 0) > 0
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
