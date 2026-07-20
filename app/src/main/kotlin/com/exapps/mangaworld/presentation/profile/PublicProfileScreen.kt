
package com.exapps.mangaworld.presentation.profile
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



// =====================================================================================
// ViewModel & State — business logic is unchanged from the original implementation.
// =====================================================================================

@Stable
data class PublicProfileUiState(
    val profile: CommunityProfile? = null,
    val lists: List<CustomUserList> = emptyList(),
    val activity: List<CommunityComment> = emptyList(),
    val selectedListId: String? = null,
    val listItems: List<CustomUserListItem> = emptyList(),
    val readingLists: Map<String, List<FavoriteManga>> = emptyMap()
)

@HiltViewModel
class PublicProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val communityRepository: CommunityRepository,
    private val libraryRepository: LibraryRepository,
    sessionManager: com.exapps.mangaworld.core.firebase.FirebaseSessionManager
) : ViewModel() {
    private val userId: String = savedStateHandle["userId"] ?: ""

    val isOwnProfile: Boolean = userId == sessionManager.currentUserId()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _selectedListId = MutableStateFlow<String?>(null)
    private val _listItems = _selectedListId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else communityRepository.observePublicListItems(userId, id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _readingLists = MutableStateFlow<Map<String, List<FavoriteManga>>>(emptyMap())

    val state = combine(
        combine(
            communityRepository.observePublicProfile(userId),
            communityRepository.observePublicLists(userId),
            communityRepository.observePublicActivity(userId)
        ) { profile, lists, activity -> Triple(profile, lists, activity) },
        combine(
            _selectedListId,
            _listItems,
            _readingLists
        ) { selectedId, items, readingLists -> Triple(selectedId, items, readingLists) }
    ) { (profile, lists, activity), (selectedId, items, readingLists) ->
        PublicProfileUiState(
            profile = profile,
            lists = if (profile?.showListsPublic == true) lists else emptyList(),
            activity = if (profile?.showActivityPublic == true) activity else emptyList(),
            selectedListId = selectedId,
            listItems = items,
            readingLists = if (profile?.showLibraryPublic == true) readingLists else emptyMap()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PublicProfileUiState())

    init {
        if (isOwnProfile) {
            viewModelScope.launch {
                val statuses = listOf("reading", "completed", "plan_to_read", "on_hold", "dropped")
                val map = mutableMapOf<String, List<FavoriteManga>>()
                for (status in statuses) {
                    map[status] = libraryRepository.getFavoritesByStatus(status)
                }
                _readingLists.value = map
            }
        }
    }

    fun toggleListExpand(listId: String) {
        _selectedListId.value = if (_selectedListId.value == listId) null else listId
    }

    fun toggleFollow() {
        _isFollowing.value = !_isFollowing.value
    }
}

// =====================================================================================
// Design constants — local to this screen (spacing / sizing tokens only, no new colors)
// =====================================================================================

private val HeroCoverHeight = 208.dp
private val HeroOverlap = 56.dp
private val AvatarSize = 96.dp

// =====================================================================================
// Screen
// =====================================================================================

@Composable
fun PublicProfileScreen(onBack: () -> Unit, onItemClick: (sourceId: String, slug: String) -> Unit = { _, _ -> }, viewModel: PublicProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFollowing by viewModel.isFollowing.collectAsStateWithLifecycle()
    val profile = state.profile
    val isOwnProfile = viewModel.isOwnProfile

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MangaColors.Background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            PublicProfileHero(
                profile = profile,
                listsCount = state.lists.size,
                activityCount = state.activity.size,
                isOwnProfile = isOwnProfile,
                isFollowing = isFollowing,
                onBack = onBack,
                onToggleFollow = { viewModel.toggleFollow() }
            )
        }

        if (state.lists.isNotEmpty()) {
            item {
                PublicListsSection(
                    lists = state.lists,
                    selectedListId = state.selectedListId,
                    listItems = state.listItems,
                    onToggleExpand = { viewModel.toggleListExpand(it) },
                    onItemClick = onItemClick
                )
            }
        }

        if (state.profile?.showLibraryPublic == true) {
            item {
                PublicLibrarySection(
                    readingLists = state.readingLists,
                    isOwnProfile = viewModel.isOwnProfile,
                    onItemClick = onItemClick
                )
            }
        }

        if (state.activity.isNotEmpty()) {
            item {
                ActivitySection(activity = state.activity, username = profile?.username)
            }
        }

        val hasLibrary = state.profile?.showLibraryPublic == true && state.readingLists.values.any { it.isNotEmpty() }
        if (state.lists.isEmpty() && state.activity.isEmpty() && !hasLibrary) {
            item {
                EmptyPublicContent()
            }
        }
    }
}

// =====================================================================================
// Hero header — cover gradient + overlapping avatar + follow action + compact stats
// =====================================================================================

@Composable
private fun PublicProfileHero(
    profile: CommunityProfile?,
    listsCount: Int,
    activityCount: Int,
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // Cover: banner image or gradient fallback
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HeroCoverHeight)
        ) {
            if (!profile?.bannerUrl.isNullOrBlank()) {
                AsyncImage(
                    model = profile.bannerUrl,
                    contentDescription = stringResource(R.string.profile_cover),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Gradient overlay for readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MangaColors.Background.copy(alpha = 0.7f))
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(MangaColors.PrimaryDim.copy(alpha = 0.45f), MangaColors.Background)
                            )
                        )
                        .drawBehind {
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(MangaColors.Cyan.copy(alpha = 0.22f), Color.Transparent),
                                    center = Offset(size.width * 0.82f, size.height * 0.28f),
                                    radius = size.width * 0.7f
                                )
                            )
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(MangaColors.Pink.copy(alpha = 0.14f), Color.Transparent),
                                    center = Offset(size.width * 0.18f, size.height * 1.05f),
                                    radius = size.width * 0.65f
                                )
                            )
                        }
                )
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(12.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.36f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
        }

        // Info column — pulled up over the tail of the cover
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HeroCoverHeight - HeroOverlap)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ProfileAvatar(profile = profile)

                if (!isOwnProfile) {
                    FollowButton(isFollowing = isFollowing, onClick = onToggleFollow)
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile?.username ?: stringResource(R.string.user),
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                if (!profile?.badgeLabel.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    BadgePill(text = profile.badgeLabel, tint = MangaColors.PrimaryLight, tintBg = MangaColors.GlowPurple)
                }
            }

            if (!profile?.bio.isNullOrBlank()) {
                Text(
                    text = profile.bio,
                    color = MangaColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, end = 24.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatChip(modifier = Modifier.weight(1f), value = listsCount.toString(), label = stringResource(R.string.public_lists_alt))
                StatChip(modifier = Modifier.weight(1f), value = activityCount.toString(), label = stringResource(R.string.recent_activity_alt))
            }
        }
    }
}

@Composable
private fun ProfileAvatar(profile: CommunityProfile?) {
    Box(
        modifier = Modifier
            .size(AvatarSize)
            .clip(CircleShape)
            .background(MangaColors.PrimaryLight.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(AvatarSize - 6.dp)
                .clip(CircleShape)
                .background(MangaColors.Background),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(AvatarSize - 12.dp)
                    .clip(CircleShape)
                    .background(MangaColors.GlowPurple),
                contentAlignment = Alignment.Center
            ) {
                if (!profile?.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = profile.avatarUrl,
                        contentDescription = stringResource(R.string.profile_image),
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = (profile?.username ?: "U").take(1).uppercase(),
                        color = MangaColors.PrimaryLight,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgePill(text: String, tint: Color, tintBg: Color) {
    Text(
        text = text,
        color = tint,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tintBg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun StatChip(modifier: Modifier = Modifier, value: String, label: String) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(label, color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FollowButton(isFollowing: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        colors = if (isFollowing) {
            ButtonDefaults.buttonColors(containerColor = MangaColors.SurfaceHigh, contentColor = MangaColors.OnSurfaceVariant)
        } else {
            ButtonDefaults.buttonColors(containerColor = MangaColors.Pink, contentColor = Color.White)
        },
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 11.dp)
    ) {
        Icon(
            if (isFollowing) Icons.Filled.PersonRemove else Icons.Filled.PersonAdd,
            contentDescription = null,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (isFollowing) stringResource(R.string.unfollow) else stringResource(R.string.follow),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// =====================================================================================
// Public Lists
// =====================================================================================

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = MangaColors.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PublicListsSection(
    lists: List<CustomUserList>,
    selectedListId: String?,
    listItems: List<CustomUserListItem>,
    onToggleExpand: (String) -> Unit,
    onItemClick: (sourceId: String, slug: String) -> Unit
) {
    Column(Modifier.padding(top = 32.dp)) {
        SectionHeader(title = stringResource(R.string.public_lists), subtitle = stringResource(R.string.fmt_032, lists.size))
        Spacer(Modifier.height(14.dp))
        lists.forEach { list ->
            PublicListCard(
                list = list,
                isExpanded = selectedListId == list.id,
                listItems = if (selectedListId == list.id) listItems else emptyList(),
                onToggleExpand = { onToggleExpand(list.id) },
                onItemClick = onItemClick
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PublicListCard(
    list: CustomUserList,
    isExpanded: Boolean = false,
    listItems: List<CustomUserListItem> = emptyList(),
    onToggleExpand: () -> Unit = {},
    onItemClick: (sourceId: String, slug: String) -> Unit = { _, _ -> }
) {
    val fallbackColor = remember(list.id) {
        val colors = listOf(MangaColors.PrimaryDim, MangaColors.CyanDim, MangaColors.Pink.copy(alpha = 0.5f), MangaColors.Orange.copy(alpha = 0.5f), MangaColors.Green.copy(alpha = 0.4f))
        colors[list.hashCode().and(0x7FFFFFFF) % colors.size]
    }
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MangaColors.SurfaceContainer)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            if (list.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = list.coverUrl,
                    contentDescription = list.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(colors = listOf(fallbackColor, MangaColors.SurfaceContainer)))
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                if (list.genres.isNotEmpty()) {
                    Text(
                        text = list.genres.first().uppercase(),
                        color = MangaColors.Cyan,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (list.rating > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = MangaColors.Yellow, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(String.format("%.1f", list.rating), color = MangaColors.Yellow, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(
                text = list.name,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (list.description.isNotBlank()) {
                Text(
                    text = list.description,
                    color = MangaColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.BookmarkBorder, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isExpanded) stringResource(R.string.hide_items) else stringResource(R.string.fmt_031, list.itemCount),
                    color = MangaColors.Cyan,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (isExpanded && listItems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listItems, key = { it.mangaId }) { item ->
                        PublicListItemCard(item = item, onItemClick = { onItemClick(item.sourceId, item.slug) })
                    }
                }
            } else if (isExpanded && listItems.isEmpty()) {
                Text(
                    stringResource(R.string.list_empty),
                    color = MangaColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

// =====================================================================================
// Public List Item Card
// =====================================================================================

@Composable
private fun PublicLibrarySection(
    readingLists: Map<String, List<FavoriteManga>>,
    isOwnProfile: Boolean,
    onItemClick: (sourceId: String, slug: String) -> Unit
) {
    val statusLabels = mapOf(
        "reading" to stringResource(R.string.library_reading),
        "completed" to stringResource(R.string.library_read),
        "plan_to_read" to stringResource(R.string.library_plan_to_read),
        "on_hold" to stringResource(R.string.library_on_hold),
        "dropped" to stringResource(R.string.library_dropped)
    )

    val hasAnyItems = readingLists.values.any { it.isNotEmpty() }

    Column(Modifier.padding(top = 32.dp)) {
        SectionHeader(
            title = stringResource(R.string.library_section_title),
            subtitle = if (hasAnyItems) stringResource(R.string.reading_lists) else stringResource(R.string.library_public_visible)
        )
        Spacer(Modifier.height(14.dp))

        if (isOwnProfile && hasAnyItems) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MangaColors.SurfaceContainer)
                    .padding(16.dp)
            ) {
                statusLabels.forEach { (status, label) ->
                    val items = readingLists[status] ?: emptyList()
                    if (items.isNotEmpty()) {
                        Text(label, color = MangaColors.PrimaryLight, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(items.size) { index ->
                                val manga = items[index]
                                PublicLibraryMangaCard(
                                    manga = manga,
                                    onClick = { onItemClick(manga.source.id, manga.slug) }
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MangaColors.SurfaceContainer)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.BookmarkBorder,
                    contentDescription = null,
                    tint = MangaColors.Muted,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.library_public_visible),
                    color = MangaColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PublicLibraryMangaCard(manga: FavoriteManga, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(110.dp)
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MangaColors.SurfaceHigh)
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                if (manga.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = manga.coverUrl,
                        contentDescription = manga.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MangaColors.PrimaryDim)
                    )
                }
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, MangaColors.SurfaceHigh))
                    )
                )
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    manga.title,
                    color = MangaColors.OnSurface,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    manga.source.displayName,
                    color = MangaColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PublicListItemCard(item: CustomUserListItem, onItemClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MangaColors.SurfaceHigh)
            .clickable(onClick = onItemClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(MangaColors.SurfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.title.take(2), color = MangaColors.Primary)
                }
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(
                item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall
            )
            if (item.rating > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = MangaColors.Yellow, modifier = Modifier.size(10.dp))
                    Text(String.format("%.1f", item.rating), color = MangaColors.Yellow, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// =====================================================================================
// Recent Activity
// =====================================================================================

@Composable
private fun ActivitySection(activity: List<CommunityComment>, username: String?) {
    Column(Modifier.padding(top = 32.dp)) {
        SectionHeader(
            title = stringResource(R.string.recent_activity),
            subtitle = if (!username.isNullOrBlank()) stringResource(R.string.fmt_044, username) else stringResource(R.string.last_updates)
        )
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MangaColors.SurfaceContainer)
        ) {
            activity.forEachIndexed { index, comment ->
                ActivityRow(comment = comment)
                if (index < activity.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .padding(horizontal = 14.dp)
                            .background(MangaColors.OnSurface.copy(alpha = 0.05f))
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(comment: CommunityComment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MangaColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.authorName,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium
                )
                if (comment.authorBadge.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(comment.authorBadge, color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = comment.text,
                color = MangaColors.OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =====================================================================================
// Empty state
// =====================================================================================

@Composable
private fun EmptyPublicContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MangaColors.GlowCyan),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = MangaColors.Cyan,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.str_435),
            color = MangaColors.OnSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.str_377),
            color = MangaColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

