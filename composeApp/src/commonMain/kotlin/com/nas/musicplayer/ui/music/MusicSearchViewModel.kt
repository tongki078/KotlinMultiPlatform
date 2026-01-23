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
                // Top 100 데이터도 검색 결과와 동일하게 정제 로직을 거치도록 수정
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
        if (query.isBlank()) {
            loadTop100()
            return
        }
        
        _uiState.update { it.copy(searchQuery = query) }

        viewModelScope.launch {
            repository.addRecentSearch(query)
            _uiState.update { it.copy(isLoading = true) }
            try {
                val searchResult = musicApiService.search(query).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
                _uiState.update { it.copy(songs = searchResult, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 경로와 파일명에서 실제 아티스트와 제목을 정밀하게 추출하는 로직
     */
    private fun cleanSongInfo(song: Song): Song {
        val fileName = song.name ?: ""
        // 1. 확장자 제거
        var cleanName = fileName.replace(Regex("\\.(mp3|flac|m4a|wav)$", RegexOption.IGNORE_CASE), "").trim()
        
        // 2. 앞쪽의 트랙 번호 제거 (예: "084. ", "01-", "1. ")
        cleanName = cleanName.replace(Regex("^\\d+[.\\-_\\s]+"), "").trim()

        // 3. "가수 - 제목" 형식 파싱
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
            // 형식이 아닐 경우 폴더명 활용
            val folderInfo = extractAlbumAndArtistFromPath(song)
            song.copy(
                name = cleanName,
                artist = folderInfo.first,
                albumName = folderInfo.second
            )
        }

        // Top 100 등 id가 0으로 오는 경우 재생 리스트 인덱싱을 위해 고유 ID 생성
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
        // MUSIC/국내/가수명/앨범명 형태일 경우 대응
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
