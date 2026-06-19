package com.exapps.mangaworld.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.firebase.FirebaseSyncManager
import com.exapps.mangaworld.core.firebase.FirebaseTopicManager
import com.exapps.mangaworld.core.widget.WidgetShortcutCoordinator
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.Stable
import javax.inject.Inject

enum class LibraryTab(val label: String) { FAVORITES("المفضلة"), HISTORY("السجل") }

@Stable
data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.FAVORITES,
    val favorites: List<FavoriteManga> = emptyList(),
    val history: List<ReadingHistoryItem> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val firebaseSyncManager: FirebaseSyncManager,
    private val firebaseTopicManager: FirebaseTopicManager,
    private val widgetShortcutCoordinator: WidgetShortcutCoordinator
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.getFavorites().collect { favs ->
                _state.update { it.copy(favorites = favs) }
            }
        }
        viewModelScope.launch {
            repo.getReadingHistory().collect { hist ->
                _state.update { it.copy(history = hist) }
            }
        }
    }

    fun selectTab(tab: LibraryTab) = _state.update { it.copy(activeTab = tab) }
    fun removeFavorite(id: String) = viewModelScope.launch {
        repo.removeFavorite(id)
        runCatching { firebaseTopicManager.unsubscribeFromManga(id) }
        runCatching { firebaseSyncManager.pushLocalSnapshot() }
        widgetShortcutCoordinator.refreshWidgets()
    }

    fun removeHistory(id: String) = viewModelScope.launch {
        repo.removeFromHistory(id)
        runCatching { firebaseSyncManager.pushLocalSnapshot() }
        widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
    }

    fun clearHistory() = viewModelScope.launch {
        repo.clearHistory()
        runCatching { firebaseSyncManager.pushLocalSnapshot() }
        widgetShortcutCoordinator.refreshWidgetsAndShortcuts()
    }
}
