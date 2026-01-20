package com.nas.musicplayer.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.nas.musicplayer.Album
import com.nas.musicplayer.Artist
import com.nas.musicplayer.Song
import com.nas.musicplayer.MusicRepository
import com.nas.musicplayer.db.RecentSearch
import com.nas.musicplayer.network.MusicApiServiceImpl
import com.nas.musicplayer.network.httpClient
import com.nas.musicplayer.network.toSongList
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MusicSearchUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val selectedArtist: Artist? = null,
    val isArtistLoading: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val albums: List<Album> = emptyList()
)

class MusicSearchViewModel(private val repository: MusicRepository) : ViewModel() {

    private val musicApiService = MusicApiServiceImpl(httpClient)

    private val _uiState = MutableStateFlow(MusicSearchUiState())
    val uiState: StateFlow<MusicSearchUiState> = _uiState.asStateFlow()

    init {
        loadTop100()
        viewModelScope.launch {
            repository.recentSearches.collect { searches ->
                _uiState.update { it.copy(recentSearches = searches) }
            }
        }
    }

    // Factory 추가
    companion object {
        fun Factory(repository: MusicRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MusicSearchViewModel(repository)
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            loadTop100()
        }
    }

    fun loadTop100() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, searchQuery = "") }
            try {
                val top100Songs = musicApiService.getTop100().toSongList().filter { !it.isDir }.map {
                    it.copy(artist = it.parentPath ?: "Unknown Artist", albumName = it.parentPath ?: "Unknown Album")
                }
                _uiState.update { it.copy(songs = top100Songs, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun performSearch(query: String = _uiState.value.searchQuery) {
        if (query.isBlank()) {
            loadTop100()
            return
        }
        
        _uiState.update { it.copy(searchQuery = query) }

        viewModelScope.launch {
            repository.addRecentSearch(query)
            _uiState.update { it.copy(isLoading = true) }
            try {
                val searchResult = musicApiService.search(query).toSongList().filter { !it.isDir }.map {
                    it.copy(artist = it.parentPath ?: "Unknown Artist", albumName = it.parentPath ?: "Unknown Album")
                }
                _uiState.update { it.copy(songs = searchResult, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch {
            repository.deleteRecentSearch(query)
        }
    }

    fun clearAllRecentSearches() {
        viewModelScope.launch {
            repository.clearAllRecentSearches()
        }
    }

    fun loadArtistDetails(artistName: String, fallbackImageUrl: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isArtistLoading = true) }
            try {
                val relatedSongs = musicApiService.search(artistName).toSongList().filter { !it.isDir }.map {
                    it.copy(artist = it.parentPath ?: "Unknown Artist", albumName = it.parentPath ?: "Unknown Album")
                }
                val artistInfo = Artist(
                    name = artistName,
                    imageUrl = fallbackImageUrl,
                    popularSongs = relatedSongs
                )
                _uiState.update {
                    it.copy(selectedArtist = artistInfo, isArtistLoading = false)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isArtistLoading = false) }
            }
        }
    }
}
