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
    val selectedAlbum: Album? = null,
    val isAlbumLoading: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val albums: List<Album> = emptyList(),
    val downloadingSongIds: Set<Long> = emptySet() // 다운로드 중인 곡 ID 목록 추가
)

class MusicSearchViewModel(private val repository: MusicRepository) : ViewModel() {

    private val musicApiService = MusicApiServiceImpl(httpClient)
    private var allLocalSongs: List<Song> = emptyList()

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

    fun setLocalSongs(songs: List<Song>) {
        allLocalSongs = songs
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
                    .distinctBy { it.name }
                _uiState.update { it.copy(songs = top100Songs, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun performSearch(query: String = _uiState.value.searchQuery) {
        val trimmedQuery = query.trim().replace(Regex("\\.$"), "")
        if (trimmedQuery.isBlank()) {
            loadTop100()
            return
        }

        val localResults = allLocalSongs.filter {
            (it.name?.contains(trimmedQuery, ignoreCase = true) == true) ||
            it.artist.contains(trimmedQuery, ignoreCase = true) ||
            it.albumName.contains(trimmedQuery, ignoreCase = true)
        }
        
        _uiState.update { it.copy(
            searchQuery = trimmedQuery, 
            songs = localResults,
            isLoading = true 
        ) }

        viewModelScope.launch {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            repository.addRecentSearch(trimmedQuery, timestamp)

            try {
                val networkResult = musicApiService.search(trimmedQuery).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
                    .distinctBy { it.name }
                
                val finalResult = (localResults + networkResult).distinctBy { it.name + it.artist }
                _uiState.update { it.copy(songs = finalResult, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // 다운로드 상태 관리 함수 추가
    fun startDownloading(songId: Long) {
        _uiState.update { it.copy(downloadingSongIds = it.downloadingSongIds + songId) }
    }

    fun stopDownloading(songId: Long) {
        _uiState.update { it.copy(downloadingSongIds = it.downloadingSongIds - songId) }
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
            val cachedSongs = _uiState.value.songs
                .filter { it.artist.contains(artistName, ignoreCase = true) }
                .distinctBy { it.name }

            if (cachedSongs.isNotEmpty()) {
                val cachedArtist = Artist(
                    name = artistName,
                    imageUrl = fallbackImageUrl ?: cachedSongs.firstOrNull()?.metaPoster,
                    popularSongs = cachedSongs
                )
                _uiState.update { it.copy(selectedArtist = cachedArtist, isArtistLoading = false) }
                if (cachedSongs.size >= 5) return@launch
            } else {
                _uiState.update { it.copy(isArtistLoading = true) }
            }

            try {
                val relatedSongs = musicApiService.search(artistName).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
                    .filter { it.artist.contains(artistName, ignoreCase = true) }
                    .distinctBy { it.name }
                
                val artistInfo = Artist(
                    name = artistName,
                    imageUrl = fallbackImageUrl ?: relatedSongs.firstOrNull()?.metaPoster,
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

    fun loadAlbumDetails(albumName: String, artistName: String, fallbackImageUrl: String? = null) {
        viewModelScope.launch {
            val cachedSongs = _uiState.value.songs
                .filter { it.albumName == albumName && (it.artist.contains(artistName, ignoreCase = true) || artistName == "Unknown Artist") }
                .distinctBy { it.name }

            if (cachedSongs.isNotEmpty()) {
                val cachedAlbum = Album(
                    name = albumName,
                    artist = artistName,
                    songs = cachedSongs,
                    imageUrl = fallbackImageUrl ?: cachedSongs.firstOrNull()?.metaPoster
                )
                _uiState.update { it.copy(selectedAlbum = cachedAlbum, isAlbumLoading = false) }
                return@launch
            } else {
                _uiState.update { it.copy(isAlbumLoading = true) }
            }

            try {
                val searchResult = musicApiService.search(albumName).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
                    .filter { it.albumName == albumName && (it.artist.contains(artistName, ignoreCase = true) || artistName == "Unknown Artist") }
                    .distinctBy { it.name }
                
                val albumInfo = Album(
                    name = albumName,
                    artist = artistName,
                    songs = searchResult,
                    imageUrl = fallbackImageUrl ?: searchResult.firstOrNull()?.metaPoster
                )
                _uiState.update {
                    it.copy(selectedAlbum = albumInfo, isAlbumLoading = false)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isAlbumLoading = false) }
            }
        }
    }
}
