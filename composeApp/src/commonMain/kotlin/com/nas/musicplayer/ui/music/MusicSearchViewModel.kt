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
import com.nas.musicplayer.network.Artist as NetworkArtist
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.takeFrom
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Theme(
    val name: String, 
    val path: String,
    @SerialName("image_url") val imageUrl: String? = null
)

@Serializable
data class ThemeResponse(
    val charts: List<Theme>,
    val collections: List<Theme>,
    val artists: List<Theme> = emptyList(),
    val genres: List<Theme> = emptyList()
)

data class MusicSearchUiState(
    val songs: List<Song> = emptyList(),
    val themes: List<Theme> = emptyList(),
    val collectionThemes: List<Theme> = emptyList(),
    val artistThemes: List<Theme> = emptyList(),
    val genreThemes: List<Theme> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val selectedArtist: Artist? = null,
    val isArtistLoading: Boolean = false,
    val selectedAlbum: Album? = null,
    val isAlbumLoading: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val albums: List<Album> = emptyList(),
    val downloadingSongIds: Set<Long> = emptySet(),
    val downloadedSongKeys: Set<String> = emptySet(),
    val artistGrid: List<NetworkArtist> = emptyList() // 아티스트 그리드용 데이터
)

class MusicSearchViewModel(private val repository: MusicRepository) : ViewModel() {

    private val musicApiService = MusicApiServiceImpl(httpClient)
    private var allLocalSongs: List<Song> = emptyList()
    private val pythonBaseUrl = "http://192.168.0.2:4444"

    private val _uiState = MutableStateFlow(MusicSearchUiState())
    val uiState: StateFlow<MusicSearchUiState> = _uiState.asStateFlow()

    init {
        loadTop100()
        loadThemes()
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

    // 아티스트 그리드 데이터 로드
    fun loadArtists(folderType: String) {
        viewModelScope.launch {
            try {
                val response = httpClient.get("$pythonBaseUrl/api/library/artists/$folderType").body<List<NetworkArtist>>()
                _uiState.update { it.copy(artistGrid = response) }
            } catch (e: Exception) {
                println("Failed to load artists: ${e.message}")
            }
        }
    }

    fun setLocalSongs(songs: List<Song>) {
        allLocalSongs = songs
        updateDownloadedKeys()
    }

    private fun updateDownloadedKeys() {
        val keys = allLocalSongs.map { generateMatchKey(it.artist, it.name ?: "") }.toSet()
        _uiState.update { it.copy(downloadedSongKeys = keys) }
    }

    fun isSongDownloaded(song: Song): Boolean {
        val key = generateMatchKey(song.artist, song.name ?: "")
        return _uiState.value.downloadedSongKeys.contains(key)
    }

    private fun generateMatchKey(artist: String, title: String): String {
        val cleanArtist = artist.replace(Regex("""[^a-zA-Z0-9가-힣ㄱ-ㅎㅏ-ㅣ]"""), "").lowercase()
        val cleanTitle = title.replace(Regex("""[^a-zA-Z0-9가-힣ㄱ-ㅎㅏ-ㅣ]"""), "").lowercase()
        return "$cleanArtist-$cleanTitle"
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            loadTop100()
        }
    }

    fun loadThemes() {
        viewModelScope.launch {
            try {
                val response = httpClient.get("$pythonBaseUrl/api/themes").body<ThemeResponse>()
                _uiState.update { it.copy(
                    themes = response.charts,
                    collectionThemes = response.collections,
                    artistThemes = response.artists,
                    genreThemes = response.genres
                ) }
            } catch (e: Exception) {
                println("Failed to load themes: ${e.message}")
            }
        }
    }

    fun loadThemeDetails(theme: Theme) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, songs = emptyList()) }
            try {
                val songs = httpClient.get {
                    url {
                        takeFrom(pythonBaseUrl)
                        appendPathSegments("api", "theme-details")
                        appendPathSegments(theme.path.split("/"))
                    }
                }.body<List<Song>>()

                val finalSongs = songs.map { cleanSongInfo(it) }

                _uiState.update { it.copy(
                    songs = finalSongs,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                println("Failed to load theme details: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun loadTop100() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, searchQuery = "", songs = emptyList()) }
            try {
                val response = httpClient.get("$pythonBaseUrl/api/top100").body<List<Song>>()
                val pythonWeeklySongs = response.map { cleanSongInfo(it) }
                
                _uiState.update { it.copy(songs = pythonWeeklySongs, isLoading = false) }
            } catch (e: Exception) {
                println("Top100 Load Error: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun performSearch(query: String = _uiState.value.searchQuery) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            loadTop100()
            return
        }

        _uiState.update { it.copy(searchQuery = trimmedQuery, isLoading = true, songs = emptyList()) }

        viewModelScope.launch {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            repository.addRecentSearch(trimmedQuery, timestamp)

            try {
                val pythonResults = httpClient.get("$pythonBaseUrl/api/search") {
                    parameter("q", trimmedQuery)
                }.body<List<Song>>().map { cleanSongInfo(it) }

                val networkResult = musicApiService.search(trimmedQuery).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }

                val finalResult = (pythonResults + networkResult).distinctBy { "${it.name}-${it.artist}" }
                
                _uiState.update { it.copy(songs = finalResult, isLoading = false) }
            } catch (e: Exception) {
                println("Search error: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun startDownloading(songId: Long) {
        _uiState.update { it.copy(downloadingSongIds = it.downloadingSongIds + songId) }
    }

    fun stopDownloading(songId: Long) {
        _uiState.update { it.copy(downloadingSongIds = it.downloadingSongIds - songId) }
    }

    private fun cleanSongInfo(song: Song): Song {
        val fileName = song.name ?: ""
        var cleanName = fileName.replace(Regex("""\.(mp3|flac|m4a|wav|dsf)$""", RegexOption.IGNORE_CASE), "").trim()
        cleanName = cleanName.replace(Regex("""^\d+[\s\.\-_]+"""), "").trim()

        val cleanedSong = if (cleanName.contains(" - ")) {
            val parts = cleanName.split(" - ", limit = 2)
            song.copy(
                name = parts[1].trim(),
                artist = parts[0].trim(),
                isDir = false
            )
        } else {
            song.copy(name = cleanName, isDir = false)
        }

        return if (cleanedSong.id == 0L) {
            val uniqueKey = "${cleanedSong.artist}-${cleanedSong.name}-${cleanedSong.streamUrl}"
            cleanedSong.copy(id = uniqueKey.hashCode().toLong())
        } else {
            cleanedSong
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
            _uiState.update { it.copy(isAlbumLoading = true) }
            try {
                val searchResult = musicApiService.search(albumName).toSongList()
                    .filter { !it.isDir }
                    .map { cleanSongInfo(it) }
                    .filter { it.albumName == albumName }
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
