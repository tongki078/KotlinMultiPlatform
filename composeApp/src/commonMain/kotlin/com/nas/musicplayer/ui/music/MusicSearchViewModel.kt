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
import kotlinx.datetime.Clock

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
                val top100Songs = musicApiService.getTop100().toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
                _uiState.update { it.copy(songs = top100Songs, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun performSearch(query: String = _uiState.value.searchQuery) {
        println("MusicSearchViewModel: performSearch entry - query: '$query'")
        
        // 음성 인식 결과의 앞뒤 공백 및 마침표 제거
        val trimmedQuery = query.trim().replace(Regex("\\.$"), "")
        println("MusicSearchViewModel: trimmed query: '$trimmedQuery'")

        if (trimmedQuery.isBlank()) {
            println("MusicSearchViewModel: Query is blank, loading top 100")
            loadTop100()
            return
        }
        
        _uiState.update { it.copy(searchQuery = trimmedQuery, isLoading = true) }

        viewModelScope.launch {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            repository.addRecentSearch(trimmedQuery, timestamp)

            try {
                println("MusicSearchViewModel: Fetching results from API for '$trimmedQuery'")
                val searchResult = musicApiService.search(trimmedQuery).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
                println("MusicSearchViewModel: Found ${searchResult.size} results")
                _uiState.update { it.copy(songs = searchResult, isLoading = false) }
            } catch (e: Exception) {
                println("MusicSearchViewModel: Search error - ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun cleanSongInfo(song: Song): Song {
        val fileName = song.name ?: ""
        var cleanName = fileName.replace(Regex("\\.(mp3|flac|m4a|wav)$", RegexOption.IGNORE_CASE), "").trim()
        cleanName = cleanName.replace(Regex("^\\d+[.\\-_\\s]+"), "").trim()

        val cleanedSong = if (cleanName.contains(" - ")) {
            val parts = cleanName.split(" - ", limit = 2)
            val artistPart = parts[0].trim()
            val titlePart = parts[1].trim()
            
            song.copy(
                name = titlePart,
                artist = artistPart,
                albumName = extractAlbumName(song)
            )
        } else {
            val folderInfo = extractAlbumAndArtistFromPath(song)
            song.copy(
                name = cleanName,
                artist = folderInfo.first,
                albumName = folderInfo.second
            )
        }

        return if (cleanedSong.id == 0L) {
            cleanedSong.copy(id = (cleanedSong.streamUrl ?: cleanedSong.name).hashCode().toLong())
        } else {
            cleanedSong
        }
    }

    private fun extractAlbumName(song: Song): String {
        return song.parentPath?.split("/")?.lastOrNull()?.replace("MUSIC/", "") ?: "Unknown Album"
    }

    private fun extractAlbumAndArtistFromPath(song: Song): Pair<String, String> {
        val pathParts = song.parentPath?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
        val lastFolder = pathParts.lastOrNull() ?: "Unknown"
        return if (pathParts.size >= 2) {
            Pair(pathParts[pathParts.size - 2], lastFolder)
        } else {
            Pair(lastFolder, lastFolder)
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
                val relatedSongs = musicApiService.search(artistName).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
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
