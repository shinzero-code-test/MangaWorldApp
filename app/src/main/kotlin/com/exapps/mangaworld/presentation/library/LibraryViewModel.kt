package com.exapps.mangaworld.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab(val label: String) { FAVORITES("المفضلة"), HISTORY("السجل") }

data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.FAVORITES,
    val favorites: List<FavoriteManga> = emptyList(),
    val history: List<ReadingHistoryItem> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: LibraryRepository
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
    fun removeFavorite(id: String) = viewModelScope.launch { repo.removeFavorite(id) }
    fun removeHistory(id: String) = viewModelScope.launch { repo.removeFromHistory(id) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
}
